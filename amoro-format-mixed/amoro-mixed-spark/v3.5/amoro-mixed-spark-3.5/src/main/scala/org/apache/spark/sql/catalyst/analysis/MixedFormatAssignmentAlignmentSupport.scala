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

package org.apache.spark.sql.catalyst.analysis

import scala.collection.compat.immutable.ArraySeq
import scala.collection.mutable

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.SQLConfHelper
import org.apache.spark.sql.catalyst.expressions.{Alias, AttributeReference, Cast, CreateNamedStruct, EvalMode, Expression, ExtractValue, GetStructField, Literal, NamedExpression}
import org.apache.spark.sql.catalyst.plans.logical.{Assignment, LogicalPlan}
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.catalyst.util.CharVarcharUtils
import org.apache.spark.sql.internal.SQLConf.StoreAssignmentPolicy
import org.apache.spark.sql.types.{StructField, StructType}

trait MixedFormatAssignmentAlignmentSupport extends CastSupport {

  self: SQLConfHelper =>

  private case class ColumnUpdate(ref: Seq[String], expr: Expression)

  /**
   * Aligns assignments to match table columns.
   * <p>
   * This method processes and reorders given assignments so that each target column gets
   * an expression it should be set to. If a column does not have a matching assignment,
   * it will be set to its current value. For example, if one passes a table with columns c1, c2
   * and an assignment c2 = 1, this method will return c1 = c1, c2 = 1.
   * <p>
   * This method also handles updates to nested columns. If there is an assignment to a particular
   * nested field, this method will construct a new struct with one field updated
   * preserving other fields that have not been modified. For example, if one passes a table with
   * columns c1, c2 where c2 is a struct with fields n1 and n2 and an assignment c2.n2 = 1,
   * this method will return c1 = c1, c2 = struct(c2.n1, 1).
   *
   * @param table a target table
   * @param assignments assignments to align
   * @return aligned assignments that match table columns
   */
  protected def alignAssignments(
      table: LogicalPlan,
      assignments: Seq[Assignment]): Seq[Assignment] = {

    val columnUpdates = assignments.map(a => ColumnUpdate(toAssignmentRef(a.key), a.value))
    val outputExprs = applyUpdates(table.output, columnUpdates)
    outputExprs.zip(table.output).map {
      case (expr, attr) => handleCharVarcharLimits(Assignment(attr, expr))
    }
  }

  private def applyUpdates(
      cols: Seq[NamedExpression],
      updates: Seq[ColumnUpdate],
      resolver: Resolver = conf.resolver,
      namePrefix: Seq[String] = Nil): Seq[Expression] = {

    // iterate through columns at the current level and find which column updates match
    cols.map { col =>
      // find matches for this column or any of its children
      val prefixMatchedUpdates = updates.filter(a => resolver(a.ref.head, col.name))
      prefixMatchedUpdates match {
        // if there is no exact match and no match for children, return the column as is
        case updates if updates.isEmpty =>
          col

        // if there is an exact match, return the assigned expression
        case Seq(update) if isExactMatch(update, col, resolver) =>
          castIfNeeded(col, update.expr, resolver)

        // if there are matches only for children
        case updates if !hasExactMatch(updates, col, resolver) =>
          col.dataType match {
            case StructType(fields) =>
              // build field expressions
              val fieldExprs = fields.zipWithIndex.map { case (field, ordinal) =>
                Alias(GetStructField(col, ordinal, Some(field.name)), field.name)()
              }

              // recursively apply this method on nested fields
              val newUpdates = updates.map(u => u.copy(ref = u.ref.tail))
              val updatedFieldExprs = applyUpdates(
                ArraySeq.unsafeWrapArray(fieldExprs),
                newUpdates,
                resolver,
                namePrefix :+ col.name)

              // construct a new struct with updated field expressions
              toNamedStruct(ArraySeq.unsafeWrapArray(fields), updatedFieldExprs)

            case otherType =>
              val colName = (namePrefix :+ col.name).mkString(".")
              throw new AnalysisException(
                "Updating nested fields is only supported for StructType " +
                  s"but $colName is of type $otherType",
                Map.empty[String, String])
          }

        // if there are conflicting updates, throw an exception
        // there are two illegal scenarios:
        // - multiple updates to the same column
        // - updates to a top-level struct and its nested fields (e.g., a.b and a.b.c)
        case updates if hasExactMatch(updates, col, resolver) =>
          val conflictingCols = updates.map(u => (namePrefix ++ u.ref).mkString("."))
          throw new AnalysisException(
            "Updates are in conflict for these columns: " +
              conflictingCols.distinct.mkString(", "),
            Map.empty[String, String])
      }
    }
  }

  private def toNamedStruct(fields: Seq[StructField], fieldExprs: Seq[Expression]): Expression = {
    val namedStructExprs = fields.zip(fieldExprs).flatMap { case (field, expr) =>
      Seq(Literal(field.name), expr)
    }
    CreateNamedStruct(namedStructExprs)
  }

  private def hasExactMatch(
      updates: Seq[ColumnUpdate],
      col: NamedExpression,
      resolver: Resolver): Boolean = {

    updates.exists(assignment => isExactMatch(assignment, col, resolver))
  }

  private def isExactMatch(
      update: ColumnUpdate,
      col: NamedExpression,
      resolver: Resolver): Boolean = {

    update.ref match {
      case Seq(namePart) if resolver(namePart, col.name) => true
      case _ => false
    }
  }

  protected def castIfNeeded(
      tableAttr: NamedExpression,
      expr: Expression,
      resolver: Resolver): Expression = {

    val storeAssignmentPolicy = conf.storeAssignmentPolicy

    // run the type check and catch type errors
    storeAssignmentPolicy match {
      case StoreAssignmentPolicy.STRICT | StoreAssignmentPolicy.ANSI =>
        if (expr.nullable && !tableAttr.nullable) {
          throw new AnalysisException(
            s"Cannot write nullable values to non-null column '${tableAttr.name}'",
            Map.empty[String, String])
        }

        // use byName = true to catch cases when struct field names don't match
        // e.g. a struct with fields (a, b) is assigned as a struct with fields (a, c) or (b, a)
        val errors = new mutable.ArrayBuffer[String]()
        val canWrite = DataTypeUtils.canWrite(
          "",
          expr.dataType,
          tableAttr.dataType,
          byName = true,
          resolver,
          tableAttr.name,
          storeAssignmentPolicy,
          err => errors += err)

        if (!canWrite) {
          throw new AnalysisException(
            s"Cannot write incompatible data:\n- ${errors.mkString("\n- ")}",
            Map.empty[String, String])
        }

      case _ => // OK
    }

    storeAssignmentPolicy match {
      case _ if tableAttr.dataType.sameType(expr.dataType) =>
        expr
      case StoreAssignmentPolicy.ANSI =>
        Cast(expr, tableAttr.dataType, Option(conf.sessionLocalTimeZone), evalMode = EvalMode.ANSI)
      case _ =>
        Cast(expr, tableAttr.dataType, Option(conf.sessionLocalTimeZone))
    }
  }

  private def toAssignmentRef(expr: Expression): Seq[String] = expr match {
    case attr: AttributeReference =>
      Seq(attr.name)
    case Alias(child, _) =>
      toAssignmentRef(child)
    case GetStructField(child, _, Some(name)) =>
      toAssignmentRef(child) :+ name
    case other: ExtractValue =>
      throw new AnalysisException(
        s"Updating nested fields is only supported for structs: $other",
        Map.empty[String, String])
    case other =>
      throw new AnalysisException(
        s"Cannot convert to a reference, unsupported expression: $other",
        Map.empty[String, String])
  }

  private def handleCharVarcharLimits(assignment: Assignment): Assignment = {
    val key = assignment.key
    val value = assignment.value

    val rawKeyType = key.transform {
      case attr: AttributeReference =>
        CharVarcharUtils.getRawType(attr.metadata)
          .map(attr.withDataType)
          .getOrElse(attr)
    }.dataType

    if (CharVarcharUtils.hasCharVarchar(rawKeyType)) {
      val newKey = key.transform {
        case attr: AttributeReference => CharVarcharUtils.cleanAttrMetadata(attr)
      }
      val newValue = CharVarcharUtils.stringLengthCheck(value, rawKeyType)
      Assignment(newKey, newValue)
    } else {
      assignment
    }
  }
}
