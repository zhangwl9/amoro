/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.amoro.server.optimizing.plan;

import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.TableFormat;
import org.apache.amoro.config.OptimizingConfig;
import org.apache.amoro.hive.table.SupportHive;
import org.apache.amoro.hive.utils.TableTypeUtil;
import org.apache.amoro.server.AmoroServiceConstants;
import org.apache.amoro.server.optimizing.OptimizingType;
import org.apache.amoro.server.optimizing.RewriteStageTask;
import org.apache.amoro.server.table.KeyedTableSnapshot;
import org.apache.amoro.server.table.TableRuntime;
import org.apache.amoro.server.table.TableSnapshot;
import org.apache.amoro.server.utils.IcebergTableUtil;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.table.MixedTable;
import org.apache.amoro.utils.ExpressionUtil;
import org.apache.amoro.utils.MixedTableUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OptimizingPlanner extends OptimizingEvaluator {
  private static final Logger LOG = LoggerFactory.getLogger(OptimizingPlanner.class);

  private final Expression partitionFilter;
  protected long processId;
  private final double availableCore;
  private final long planTime;
  private OptimizingType optimizingType;
  private final PartitionPlannerFactory partitionPlannerFactory;
  private List<RewriteStageTask> tasks;
  private List<AbstractPartitionPlan> actualPartitionPlans;
  private final long maxInputSizePerThread;

  public static OptimizingPlanner createOptimizingPlanner(
      TableRuntime tableRuntime,
      MixedTable table,
      double availableCore,
      long maxInputSizePerThread) {
    Expression partitionFilter =
        tableRuntime.getPendingInput() == null
            ? Expressions.alwaysTrue()
            : tableRuntime.getPendingInput().getPartitions().entrySet().stream()
                .map(
                    entry ->
                        ExpressionUtil.convertPartitionDataToDataFilter(
                            table, entry.getKey(), entry.getValue()))
                .reduce(Expressions::or)
                .orElse(Expressions.alwaysTrue());
    long planTime = System.currentTimeMillis();

    return new OptimizingPlanner(
        tableRuntime.getTableIdentifier(),
        tableRuntime.getOptimizingConfig(),
        table,
        IcebergTableUtil.getSnapshot(table, tableRuntime),
        partitionFilter,
        Math.max(tableRuntime.getNewestProcessId() + 1, planTime),
        availableCore,
        maxInputSizePerThread,
        tableRuntime.getLastMinorOptimizingTime(),
        tableRuntime.getLastFullOptimizingTime());
  }

  public OptimizingPlanner(
      ServerTableIdentifier identifier,
      OptimizingConfig config,
      MixedTable table,
      TableSnapshot snapshot,
      Expression partitionFilter,
      long processId,
      double availableCore,
      long maxInputSizePerThread,
      long lastMinorOptimizingTime,
      long lastFullOptimizingTime) {
    super(
        identifier,
        config,
        table,
        snapshot,
        Integer.MAX_VALUE,
        lastMinorOptimizingTime,
        lastFullOptimizingTime);
    this.partitionFilter = partitionFilter;
    this.availableCore = availableCore;
    this.planTime = System.currentTimeMillis();
    this.processId = processId;
    this.partitionPlannerFactory =
        new PartitionPlannerFactory(
            identifier,
            config,
            mixedTable,
            planTime,
            lastMinorOptimizingTime,
            lastFullOptimizingTime);
    this.maxInputSizePerThread = maxInputSizePerThread;
  }

  @Override
  protected PartitionEvaluator buildEvaluator(Pair<Integer, StructLike> partition) {
    return partitionPlannerFactory.buildPartitionPlanner(partition);
  }

  public Map<String, Long> getFromSequence() {
    return actualPartitionPlans.stream()
        .filter(p -> p.getFromSequence() != null)
        .collect(
            Collectors.toMap(
                partitionPlan -> {
                  Pair<Integer, StructLike> partition = partitionPlan.getPartition();
                  PartitionSpec spec =
                      MixedTableUtil.getMixedTablePartitionSpecById(mixedTable, partition.first());
                  return spec.partitionToPath(partition.second());
                },
                AbstractPartitionPlan::getFromSequence));
  }

  public Map<String, Long> getToSequence() {
    return actualPartitionPlans.stream()
        .filter(p -> p.getToSequence() != null)
        .collect(
            Collectors.toMap(
                partitionPlan -> {
                  Pair<Integer, StructLike> partition = partitionPlan.getPartition();
                  PartitionSpec spec =
                      MixedTableUtil.getMixedTablePartitionSpecById(mixedTable, partition.first());
                  return spec.partitionToPath(partition.second());
                },
                AbstractPartitionPlan::getToSequence));
  }

  @Override
  protected Expression getPartitionFilter() {
    return partitionFilter;
  }

  public long getTargetSnapshotId() {
    return currentSnapshot.snapshotId();
  }

  public long getTargetChangeSnapshotId() {
    if (currentSnapshot instanceof KeyedTableSnapshot) {
      return ((KeyedTableSnapshot) currentSnapshot).changeSnapshotId();
    } else {
      return AmoroServiceConstants.INVALID_SNAPSHOT_ID;
    }
  }

  @Override
  public boolean isNecessary() {
    if (!super.isNecessary()) {
      return false;
    }
    return !planTasks().isEmpty();
  }

  public List<RewriteStageTask> planTasks() {
    if (this.tasks != null) {
      return this.tasks;
    }
    long startTime = System.nanoTime();
    if (!isInitialized) {
      initEvaluator();
    }
    if (!super.isNecessary()) {
      LOG.debug("Table {} skip planning", identifier);
      return cacheAndReturnTasks(Collections.emptyList());
    }

    List<PartitionEvaluator> evaluators = new ArrayList<>(needOptimizingPlanMap.values());
    // prioritize partitions with high cost to avoid starvation
    evaluators.sort(Comparator.comparing(PartitionEvaluator::getWeight, Comparator.reverseOrder()));

    double maxInputSize = maxInputSizePerThread * availableCore;
    actualPartitionPlans = Lists.newArrayList();
    long actualInputSize = 0;
    for (PartitionEvaluator evaluator : evaluators) {
      actualPartitionPlans.add((AbstractPartitionPlan) evaluator);
      actualInputSize += evaluator.getCost();
      if (actualInputSize > maxInputSize) {
        break;
      }
    }

    double avgThreadCost = actualInputSize / availableCore;
    List<RewriteStageTask> tasks = Lists.newArrayList();
    for (AbstractPartitionPlan partitionPlan : actualPartitionPlans) {
      tasks.addAll(partitionPlan.splitTasks((int) (actualInputSize / avgThreadCost)));
    }
    if (!tasks.isEmpty()) {
      if (actualPartitionPlans.stream()
          .anyMatch(plan -> plan.getOptimizingType() == OptimizingType.FULL)) {
        optimizingType = OptimizingType.FULL;
      } else if (actualPartitionPlans.stream()
          .anyMatch(plan -> plan.getOptimizingType() == OptimizingType.MAJOR)) {
        optimizingType = OptimizingType.MAJOR;
      } else {
        optimizingType = OptimizingType.MINOR;
      }
    }
    long endTime = System.nanoTime();
    LOG.info(
        "{} finish plan, type = {}, get {} tasks, cost {} ns, {} ms maxInputSize {} actualInputSize {}",
        identifier,
        getOptimizingType(),
        tasks.size(),
        endTime - startTime,
        (endTime - startTime) / 1_000_000,
        maxInputSize,
        actualInputSize);
    return cacheAndReturnTasks(tasks);
  }

  private List<RewriteStageTask> cacheAndReturnTasks(List<RewriteStageTask> tasks) {
    this.tasks = tasks;
    return this.tasks;
  }

  public long getPlanTime() {
    return planTime;
  }

  public OptimizingType getOptimizingType() {
    return optimizingType;
  }

  public long getProcessId() {
    return processId;
  }

  private static class PartitionPlannerFactory {
    protected final OptimizingConfig config;
    protected final ServerTableIdentifier identifier;
    protected final long lastMinorOptimizingTime;
    protected final long lastFullOptimizingTime;
    private final MixedTable mixedTable;
    private final String hiveLocation;
    private final long planTime;

    public PartitionPlannerFactory(
        ServerTableIdentifier identifier,
        OptimizingConfig config,
        MixedTable mixedTable,
        long planTime,
        long lastMinorOptimizingTime,
        long lastFullOptimizingTime) {
      this.identifier = identifier;
      this.config = config;
      this.mixedTable = mixedTable;
      this.planTime = planTime;
      this.lastFullOptimizingTime = lastFullOptimizingTime;
      this.lastMinorOptimizingTime = lastMinorOptimizingTime;
      if (TableTypeUtil.isHive(mixedTable)) {
        this.hiveLocation = (((SupportHive) mixedTable).hiveLocation());
      } else {
        this.hiveLocation = null;
      }
    }

    public PartitionEvaluator buildPartitionPlanner(Pair<Integer, StructLike> partition) {
      if (TableFormat.ICEBERG.equals(mixedTable.format())) {
        return new IcebergPartitionPlan(
            identifier,
            config,
            mixedTable,
            partition,
            planTime,
            lastMinorOptimizingTime,
            lastFullOptimizingTime);
      } else {
        if (TableTypeUtil.isHive(mixedTable)) {
          return new MixedHivePartitionPlan(
              identifier,
              mixedTable,
              config,
              partition,
              hiveLocation,
              planTime,
              lastMinorOptimizingTime,
              lastFullOptimizingTime);
        } else {
          return new MixedIcebergPartitionPlan(
              identifier,
              mixedTable,
              config,
              partition,
              planTime,
              lastMinorOptimizingTime,
              lastFullOptimizingTime);
        }
      }
    }
  }
}
