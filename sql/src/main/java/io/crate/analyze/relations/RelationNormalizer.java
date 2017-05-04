/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.analyze.relations;

import io.crate.analyze.*;
import io.crate.analyze.symbol.FieldReplacer;
import io.crate.metadata.Functions;
import io.crate.metadata.ReplaceMode;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.table.Operation;
import io.crate.sql.tree.QualifiedName;

import java.util.Iterator;

/**
 * The RelationNormalizer tries to merge the tree of relations in a QueriedSelectRelation into a single QueriedRelation.
 * The merge occurs from the top level to the deepest one. For each level, it verifies if the query is mergeable with
 * the next relation and proceed with the merge if positive. When it is not, the partially merged tree is returned.
 */
final class RelationNormalizer {

    private final NormalizerVisitor visitor;

    RelationNormalizer(Functions functions) {
        visitor = new NormalizerVisitor(functions);
    }

    public AnalyzedRelation normalize(AnalyzedRelation relation, TransactionContext transactionContext) {
        return visitor.process(relation, transactionContext);
    }

    private static class NormalizerVisitor extends AnalyzedRelationVisitor<TransactionContext, AnalyzedRelation> {

        private final Functions functions;
        private final EvaluatingNormalizer normalizer;

        NormalizerVisitor(Functions functions) {
            this.functions = functions;
            this.normalizer = EvaluatingNormalizer.functionOnlyNormalizer(functions, ReplaceMode.COPY);
        }

        @Override
        protected AnalyzedRelation visitAnalyzedRelation(AnalyzedRelation relation, TransactionContext context) {
            return relation;
        }

        @Override
        public AnalyzedRelation visitQueriedSelectRelation(QueriedSelectRelation relation, TransactionContext context) {
            QueriedRelation subRelation = relation.subRelation();
            QueriedRelation normalizedSubRelation = (QueriedRelation) process(relation.subRelation(), context);
            relation.subRelation(normalizedSubRelation);
            if (subRelation != normalizedSubRelation) {
                relation.querySpec().replace(FieldReplacer.bind(f -> {
                    if (f.relation() == subRelation) {
                        return normalizedSubRelation.getField(f.path(), Operation.READ);
                    }
                    return f;
                })::apply);
            }
            return relation;
        }

        @Override
        public AnalyzedRelation visitQueriedTable(QueriedTable table, TransactionContext context) {
            table.normalize(functions, context);
            return table;
        }

        @Override
        public AnalyzedRelation visitQueriedDocTable(QueriedDocTable table, TransactionContext context) {
            table.normalize(functions, context);
            table.analyzeWhereClause(functions, context);
            return table;
        }

        @Override
        public AnalyzedRelation visitMultiSourceSelect(MultiSourceSelect mss, TransactionContext context) {
            QuerySpec querySpec = mss.querySpec();
            querySpec.normalize(normalizer, context);
            // must create a new MultiSourceSelect because paths and query spec changed
            mss = MultiSourceSelect.createWithPushDown(mss, querySpec);
            if (mss.sources().size() == 2) {
                Iterator<RelationSource> it = mss.sources().values().iterator();
                RelationSource leftSource = it.next();
                RelationSource rightSource = it.next();
                QualifiedName left = leftSource.qualifiedName();
                QualifiedName right = rightSource.qualifiedName();
                Rewriter.tryRewriteOuterToInnerJoin(
                    normalizer,
                    JoinPairs.ofRelationsWithMergedConditions(left, right, mss.joinPairs(), false),
                    mss.querySpec().outputs(),
                    mss.querySpec(),
                    left,
                    right,
                    leftSource.querySpec(),
                    rightSource.querySpec());
            }
            return mss;
        }
    }
}
