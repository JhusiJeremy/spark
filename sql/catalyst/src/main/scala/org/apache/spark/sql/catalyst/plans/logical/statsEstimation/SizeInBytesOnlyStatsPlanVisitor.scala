/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical.statsEstimation

import org.apache.spark.sql.catalyst.expressions.AttributeMap
import org.apache.spark.sql.catalyst.plans.{LeftAnti, LeftSemi}
import org.apache.spark.sql.catalyst.plans.logical._

/**
 * An [[LogicalPlanVisitor]] that computes a single dimension for plan stats: size in bytes.
 */
object SizeInBytesOnlyStatsPlanVisitor extends LogicalPlanVisitor[Statistics] {

  /**
   * A default, commonly used estimation for unary nodes. We assume the input row number is the
   * same as the output row number, and compute sizes based on the column types.
   */
  private def visitUnaryNode(p: UnaryNode): Statistics = {
    // There should be some overhead in Row object, the size should not be zero when there is
    // no columns, this help to prevent divide-by-zero error.
    val childRowSize = EstimationUtils.getSizePerRow(p.child.output)
    val outputRowSize = EstimationUtils.getSizePerRow(p.output)
    // Assume there will be the same number of rows as child has.
    var sizeInBytes = (p.child.stats.sizeInBytes * outputRowSize) / childRowSize
    if (sizeInBytes == 0) {
      // sizeInBytes can't be zero, or sizeInBytes of BinaryNode will also be zero
      // (product of children).
      sizeInBytes = 1
    }

    // Don't propagate rowCount and attributeStats, since they are not estimated here.
    Statistics(sizeInBytes = sizeInBytes, hints = p.child.stats.hints,
      cost = p.child.stats.cost + sizeInBytes,
      costDetail = generateTreeString(p, sizeInBytes, p.child.stats.costDetail))
  }

  /**
   * For leaf nodes, use its `computeStats`. For other nodes, we assume the size in bytes is the
   * product of all of the children's `computeStats`.
   */
  override def default(p: LogicalPlan): Statistics = p match {
    case p: LeafNode => p.computeStats()
    case _: LogicalPlan =>
      val sizeInBytes = p.children.map(_.stats.sizeInBytes).product
      val childrenCostDetail = p.children.map(_.stats.costDetail).mkString(" ")
      Statistics(
        sizeInBytes = sizeInBytes,
        cost = p.children.map(_.stats.sizeInBytes).sum + sizeInBytes,
        costDetail = generateTreeString(p, sizeInBytes, childrenCostDetail)
      )
  }

  def generateTreeString(p: LogicalPlan, cost: BigInt, childDetail: String): String = {
    val thisPlan = s"${p.getClass().getSimpleName()}: $cost"
    val childPlan = childDetail.replace("\n", "\n  ")
    thisPlan + "\n  " + childPlan
  }

  override def visitAggregate(p: Aggregate): Statistics = {
    if (p.groupingExpressions.isEmpty) {
      val sizeInBytes = EstimationUtils.getOutputSize(p.output, outputRowCount = 1)
      Statistics(
        sizeInBytes = sizeInBytes,
        rowCount = Some(1),
        hints = p.child.stats.hints,
        cost = p.child.stats.cost + sizeInBytes,
        costDetail = s"${p.child.stats.costDetail} ${p.getClass().getSimpleName()}: $sizeInBytes")
    } else {
      visitUnaryNode(p)
    }
  }

  override def visitDistinct(p: Distinct): Statistics = default(p)

  override def visitExcept(p: Except): Statistics = p.left.stats.copy()

  override def visitExpand(p: Expand): Statistics = {
    val sizeInBytes = visitUnaryNode(p).sizeInBytes * p.projections.length
    Statistics(
      sizeInBytes = sizeInBytes,
      cost = p.child.stats.cost + sizeInBytes,
      costDetail = s"${p.child.stats.costDetail} ${p.getClass().getSimpleName()}: $sizeInBytes")
  }

  override def visitFilter(p: Filter): Statistics = visitUnaryNode(p)

  override def visitGenerate(p: Generate): Statistics = default(p)

  override def visitGlobalLimit(p: GlobalLimit): Statistics = {
    val limit = p.limitExpr.eval().asInstanceOf[Int]
    val childStats = p.child.stats
    val rowCount: BigInt = childStats.rowCount.map(_.min(limit)).getOrElse(limit)
    // Don't propagate column stats, because we don't know the distribution after limit
    val sizeInBytes = EstimationUtils.getOutputSize(p.output, rowCount, childStats.attributeStats)
    Statistics(
      sizeInBytes = sizeInBytes,
      rowCount = Some(rowCount),
      hints = childStats.hints,
      cost = childStats.cost + sizeInBytes,
      costDetail = generateTreeString(p, sizeInBytes, childStats.costDetail))
  }

  override def visitHint(p: ResolvedHint): Statistics = p.child.stats.copy(hints = p.hints)

  override def visitOffset(p: Offset): Statistics = {
    val offset = p.offsetExpr.eval().asInstanceOf[Int]
    val childStats = p.child.stats
    val rowCount: BigInt = childStats.rowCount.map(_.-(offset).max(0)).getOrElse(0)
    val sizeInBytes = EstimationUtils.getOutputSize(p.output, rowCount, childStats.attributeStats)
    Statistics(
      sizeInBytes = sizeInBytes,
      rowCount = Some(rowCount),
      cost = childStats.cost + sizeInBytes,
      costDetail = s"${childStats.costDetail} ${p.getClass().getSimpleName()}: $sizeInBytes")
  }

  override def visitIntersect(p: Intersect): Statistics = {
    val leftSize = p.left.stats.sizeInBytes
    val rightSize = p.right.stats.sizeInBytes
    val sizeInBytes = if (leftSize < rightSize) leftSize else rightSize
    val childCost = if (leftSize < rightSize) p.left.stats.cost else p.right.stats.cost
    val childCostDetail = if (leftSize < rightSize) {
      p.left.stats.costDetail
    } else {
      p.right.stats.costDetail
    }
    Statistics(
      sizeInBytes = sizeInBytes,
      hints = p.left.stats.hints.resetForJoin(),
      cost = childCost + sizeInBytes,
      costDetail = s"${childCostDetail} ${p.getClass().getSimpleName()}: $sizeInBytes")
  }

  override def visitJoin(p: Join): Statistics = {
    p.joinType match {
      case LeftAnti | LeftSemi =>
        // LeftSemi and LeftAnti won't ever be bigger than left
        p.left.stats
      case _ =>
        // Make sure we don't propagate isBroadcastable in other joins, because
        // they could explode the size.
        val stats = default(p)
        stats.copy(hints = stats.hints.resetForJoin())
    }
  }

  override def visitLocalLimit(p: LocalLimit): Statistics = {
    val limit = p.limitExpr.eval().asInstanceOf[Int]
    val childStats = p.child.stats
    if (limit == 0) {
      // sizeInBytes can't be zero, or sizeInBytes of BinaryNode will also be zero
      // (product of children).
      Statistics(
        sizeInBytes = 1,
        rowCount = Some(0),
        hints = childStats.hints,
        cost = childStats.cost + 1,
        costDetail = generateTreeString(p, 1, childStats.costDetail))
    } else {
      // The output row count of LocalLimit should be the sum of row counts from each partition.
      // However, since the number of partitions is not available here, we just use statistics of
      // the child. Because the distribution after a limit operation is unknown, we do not propagate
      // the column stats.
      childStats.copy(
        attributeStats = AttributeMap(Nil),
        cost = childStats.cost + childStats.sizeInBytes,
        costDetail = generateTreeString(p, childStats.sizeInBytes, childStats.costDetail))
    }
  }

  override def visitPivot(p: Pivot): Statistics = default(p)

  override def visitProject(p: Project): Statistics = visitUnaryNode(p)

  override def visitRepartition(p: Repartition): Statistics = default(p)

  override def visitRepartitionByExpr(p: RepartitionByExpression): Statistics = default(p)

  override def visitSample(p: Sample): Statistics = {
    val ratio = p.upperBound - p.lowerBound
    var sizeInBytes = EstimationUtils.ceil(BigDecimal(p.child.stats.sizeInBytes) * ratio)
    if (sizeInBytes == 0) {
      sizeInBytes = 1
    }
    val sampleRows = p.child.stats.rowCount.map(c => EstimationUtils.ceil(BigDecimal(c) * ratio))
    // Don't propagate column stats, because we don't know the distribution after a sample operation
    Statistics(
      sizeInBytes,
      sampleRows,
      hints = p.child.stats.hints,
      cost = p.child.stats.cost + sizeInBytes,
      costDetail = s"${p.child.stats.costDetail} ${p.getClass().getSimpleName()}: $sizeInBytes")
  }

  override def visitScriptTransform(p: ScriptTransformation): Statistics = default(p)

  override def visitUnion(p: Union): Statistics = {
    val sizeInBytes = p.children.map(_.stats.sizeInBytes).sum
    val childrenCostDetail = p.children.map(_.stats.costDetail).mkString(" ")
    Statistics(
      sizeInBytes = sizeInBytes,
      cost = sizeInBytes * 2,
      costDetail = s"$childrenCostDetail ${p.getClass().getSimpleName()}: $sizeInBytes")
  }

  override def visitWindow(p: Window): Statistics = visitUnaryNode(p)
}
