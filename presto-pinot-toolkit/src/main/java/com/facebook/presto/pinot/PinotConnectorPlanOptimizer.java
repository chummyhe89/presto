/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.pinot;

import com.facebook.presto.expressions.LogicalRowExpressions;
import com.facebook.presto.pinot.query.PinotFilterExpressionConverter;
import com.facebook.presto.pinot.query.PinotQueryGenerator;
import com.facebook.presto.pinot.query.PinotQueryGeneratorContext;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorPlanOptimizer;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.plan.PlanVisitor;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.collect.ImmutableList;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.pinot.PinotErrorCode.PINOT_UNCLASSIFIED_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

public class PinotConnectorPlanOptimizer
        implements ConnectorPlanOptimizer
{
    private final PinotQueryGenerator pinotQueryGenerator;
    private final TypeManager typeManager;
    private final FunctionMetadataManager functionMetadataManager;
    private final LogicalRowExpressions logicalRowExpressions;
    private final StandardFunctionResolution standardFunctionResolution;

    @Inject
    public PinotConnectorPlanOptimizer(
            PinotQueryGenerator pinotQueryGenerator,
            TypeManager typeManager,
            FunctionMetadataManager functionMetadataManager,
            LogicalRowExpressions logicalRowExpressions,
            StandardFunctionResolution standardFunctionResolution)
    {
        this.pinotQueryGenerator = requireNonNull(pinotQueryGenerator, "pinot query generator is null");
        this.typeManager = requireNonNull(typeManager, "type manager is null");
        this.functionMetadataManager = requireNonNull(functionMetadataManager, "function manager is null");
        this.logicalRowExpressions = requireNonNull(logicalRowExpressions, "logical row expressions is null");
        this.standardFunctionResolution = requireNonNull(standardFunctionResolution, "standard function resolution is null");
    }

    @Override
    public PlanNode optimize(PlanNode maxSubplan,
            ConnectorSession session,
            VariableAllocator variableAllocator,
            PlanNodeIdAllocator idAllocator)
    {
        Map<TableScanNode, Void> scanNodes = maxSubplan.accept(new TableFindingVisitor(), null);
        TableScanNode pinotTableScanNode = getOnlyPinotTable(scanNodes)
                .orElseThrow(() -> new PrestoException(GENERIC_INTERNAL_ERROR,
                        "Expected to find the pinot table handle for the scan node"));
        return maxSubplan.accept(new Visitor(pinotTableScanNode, session, idAllocator), null);
    }

    private static Optional<PinotTableHandle> getPinotTableHandle(TableScanNode tableScanNode)
    {
        TableHandle table = tableScanNode.getTable();
        if (table != null) {
            ConnectorTableHandle connectorHandle = table.getConnectorHandle();
            if (connectorHandle instanceof PinotTableHandle) {
                return Optional.of((PinotTableHandle) connectorHandle);
            }
        }
        return Optional.empty();
    }

    private static Optional<TableScanNode> getOnlyPinotTable(Map<TableScanNode, Void> scanNodes)
    {
        if (scanNodes.size() == 1) {
            TableScanNode tableScanNode = scanNodes.keySet().iterator().next();
            if (getPinotTableHandle(tableScanNode).isPresent()) {
                return Optional.of(tableScanNode);
            }
        }
        return Optional.empty();
    }

    private static PlanNode replaceChildren(PlanNode node, List<PlanNode> children)
    {
        for (int i = 0; i < node.getSources().size(); i++) {
            if (children.get(i) != node.getSources().get(i)) {
                return node.replaceChildren(children);
            }
        }
        return node;
    }

    private static class TableFindingVisitor
            extends PlanVisitor<Map<TableScanNode, Void>, Void>
    {
        @Override
        public Map<TableScanNode, Void> visitPlan(PlanNode node, Void context)
        {
            Map<TableScanNode, Void> ret = new IdentityHashMap<>();
            node.getSources().forEach(source -> ret.putAll(source.accept(this, context)));
            return ret;
        }

        @Override
        public Map<TableScanNode, Void> visitTableScan(TableScanNode node, Void context)
        {
            Map<TableScanNode, Void> ret = new IdentityHashMap<>();
            ret.put(node, null);
            return ret;
        }
    }

    // Single use visitor that needs the pinot table handle
    private class Visitor
            extends PlanVisitor<PlanNode, Void>
    {
        private final PlanNodeIdAllocator idAllocator;
        private final ConnectorSession session;
        private final TableScanNode tableScanNode;
        private final IdentityHashMap<FilterNode, Void> filtersSplitUp = new IdentityHashMap<>();

        public Visitor(TableScanNode tableScanNode, ConnectorSession session, PlanNodeIdAllocator idAllocator)
        {
            this.session = session;
            this.idAllocator = idAllocator;
            this.tableScanNode = tableScanNode;
            // Just making sure that the table exists
            getPinotTableHandle(this.tableScanNode).get().getTableName();
        }

        private Optional<PlanNode> tryCreatingNewScanNode(PlanNode plan)
        {
            Optional<PinotQueryGenerator.PinotQueryGeneratorResult> pql = pinotQueryGenerator.generate(plan, session);
            if (!pql.isPresent()) {
                return Optional.empty();
            }
            PinotTableHandle pinotTableHandle = getPinotTableHandle(tableScanNode).orElseThrow(() -> new PinotException(PINOT_UNCLASSIFIED_ERROR, Optional.empty(), "Expected to find a pinot table handle"));
            PinotQueryGeneratorContext context = pql.get().getContext();
            TableHandle oldTableHandle = tableScanNode.getTable();
            LinkedHashMap<VariableReferenceExpression, PinotColumnHandle> assignments = context.getAssignments();
            boolean isQueryShort = pql.get().getGeneratedPql().isQueryShort();
            TableHandle newTableHandle = new TableHandle(
                    oldTableHandle.getConnectorId(),
                    new PinotTableHandle(pinotTableHandle.getConnectorId(), pinotTableHandle.getSchemaName(), pinotTableHandle.getTableName(), Optional.of(isQueryShort), Optional.of(pql.get().getGeneratedPql())),
                    oldTableHandle.getTransaction(),
                    oldTableHandle.getLayout());
            return Optional.of(
                    new TableScanNode(
                            idAllocator.getNextId(),
                            newTableHandle,
                            ImmutableList.copyOf(assignments.keySet()),
                            assignments.entrySet().stream().collect(toImmutableMap(Map.Entry::getKey, (e) -> (ColumnHandle) (e.getValue()))),
                            tableScanNode.getCurrentConstraint(),
                            tableScanNode.getEnforcedConstraint()));
        }

        @Override
        public PlanNode visitPlan(PlanNode node, Void context)
        {
            Optional<PlanNode> pushedDownPlan = tryCreatingNewScanNode(node);
            return pushedDownPlan.orElseGet(() -> replaceChildren(
                    node,
                    node.getSources().stream().map(source -> source.accept(this, null)).collect(toImmutableList())));
        }

        @Override
        public PlanNode visitFilter(FilterNode node, Void context)
        {
            if (filtersSplitUp.containsKey(node)) {
                return this.visitPlan(node, context);
            }
            filtersSplitUp.put(node, null);
            FilterNode nodeToRecurseInto = node;
            List<RowExpression> pushable = new ArrayList<>();
            List<RowExpression> nonPushable = new ArrayList<>();
            PinotFilterExpressionConverter pinotFilterExpressionConverter = new PinotFilterExpressionConverter(typeManager, functionMetadataManager, standardFunctionResolution);
            for (RowExpression conjunct : LogicalRowExpressions.extractConjuncts(node.getPredicate())) {
                try {
                    conjunct.accept(pinotFilterExpressionConverter, (var) -> new PinotQueryGeneratorContext.Selection(var.getName(), PinotQueryGeneratorContext.Origin.DERIVED));
                    pushable.add(conjunct);
                }
                catch (PinotException pe) {
                    nonPushable.add(conjunct);
                }
            }
            if (!pushable.isEmpty()) {
                FilterNode pushableFilter = new FilterNode(idAllocator.getNextId(), node.getSource(), logicalRowExpressions.combineConjuncts(pushable));
                Optional<FilterNode> nonPushableFilter = nonPushable.isEmpty() ? Optional.empty() : Optional.of(new FilterNode(idAllocator.getNextId(), pushableFilter, logicalRowExpressions.combineConjuncts(nonPushable)));

                filtersSplitUp.put(pushableFilter, null);
                if (nonPushableFilter.isPresent()) {
                    FilterNode nonPushableFilterNode = nonPushableFilter.get();
                    filtersSplitUp.put(nonPushableFilterNode, null);
                    nodeToRecurseInto = nonPushableFilterNode;
                }
                else {
                    nodeToRecurseInto = pushableFilter;
                }
            }
            return this.visitFilter(nodeToRecurseInto, context);
        }
    }
}
