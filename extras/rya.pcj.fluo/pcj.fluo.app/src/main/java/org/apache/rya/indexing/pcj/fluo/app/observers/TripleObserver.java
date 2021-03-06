/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.rya.indexing.pcj.fluo.app.observers;

import static org.apache.rya.indexing.pcj.fluo.app.IncrementalUpdateConstants.DELIM;

import java.util.Map;
import java.util.Set;

import org.apache.fluo.api.client.TransactionBase;
import org.apache.fluo.api.data.Bytes;
import org.apache.fluo.api.data.Column;
import org.apache.fluo.api.observer.AbstractObserver;
import org.apache.rya.api.domain.RyaStatement;
import org.apache.rya.api.model.VisibilityBindingSet;
import org.apache.rya.indexing.pcj.fluo.app.IncUpdateDAO;
import org.apache.rya.indexing.pcj.fluo.app.query.FluoQueryColumns;
import org.apache.rya.indexing.pcj.fluo.app.query.FluoQueryMetadataCache;
import org.apache.rya.indexing.pcj.fluo.app.query.MetadataCacheSupplier;
import org.apache.rya.indexing.pcj.fluo.app.query.StatementPatternIdCache;
import org.apache.rya.indexing.pcj.fluo.app.query.StatementPatternIdCacheSupplier;
import org.apache.rya.indexing.pcj.fluo.app.query.StatementPatternMetadata;
import org.apache.rya.indexing.pcj.fluo.app.util.BindingHashShardingFunction;
import org.apache.rya.indexing.pcj.storage.accumulo.VariableOrder;
import org.apache.rya.indexing.pcj.storage.accumulo.VisibilityBindingSetSerDe;
import org.apache.rya.indexing.pcj.storage.accumulo.VisibilityBindingSetStringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

/**
 * An observer that matches new Triples to the Statement Patterns that are part
 * of any PCJ that is being maintained. If the triple matches a pattern, then
 * the new result is stored as a binding set for the pattern.
 */
public class TripleObserver extends AbstractObserver {
    private static final Logger log = LoggerFactory.getLogger(TripleObserver.class);

    private static final VisibilityBindingSetSerDe BS_SERDE = new VisibilityBindingSetSerDe();
    private final FluoQueryMetadataCache QUERY_METADATA_DAO = MetadataCacheSupplier.getOrCreateCache();
    private final StatementPatternIdCache SP_ID_CACHE = StatementPatternIdCacheSupplier.getOrCreateCache();
    private static final VisibilityBindingSetStringConverter VIS_BS_CONVERTER = new VisibilityBindingSetStringConverter();

    @Override
    public ObservedColumn getObservedColumn() {
        return new ObservedColumn(FluoQueryColumns.TRIPLES, NotificationType.STRONG);
    }

    @Override
    public void process(final TransactionBase tx, final Bytes brow, final Column column) {
        // Get string representation of triple.
        final RyaStatement ryaStatement = IncUpdateDAO.deserializeTriple(brow);
        log.trace("Transaction ID: {}\nRya Statement: {}\n", tx.getStartTimestamp(), ryaStatement);
        log.trace("Beginging to process triple.");

        final String triple = IncUpdateDAO.getTripleString(ryaStatement);

        Set<String> spIDs = SP_ID_CACHE.getStatementPatternIds(tx);

        //see if triple matches conditions of any of the SP
        for (String spID: spIDs) {
            // Fetch its metadata.
            final StatementPatternMetadata spMetadata = QUERY_METADATA_DAO.readStatementPatternMetadata(tx, spID);

            log.trace("Retrieved metadata: {}", spMetadata);

            // Attempt to match the triple against the pattern.
            final String pattern = spMetadata.getStatementPattern();
            final VariableOrder varOrder = spMetadata.getVariableOrder();
            final String bindingSetString = getBindingSet(triple, pattern, varOrder);

            log.trace("Created binding set match string: {}", bindingSetString);

            // Statement matches to a binding set.
            if(bindingSetString.length() != 0) {
                // Fetch the triple's visibility label.
                final String visibility = tx.gets(brow.toString(), FluoQueryColumns.TRIPLES, "");

                //Make BindingSet and sharded row
                final VisibilityBindingSet visBindingSet = VIS_BS_CONVERTER.convert(bindingSetString, varOrder);
                visBindingSet.setVisibility(visibility);
                Bytes row = BindingHashShardingFunction.addShard(spID, varOrder, visBindingSet);

                // If this is a new Binding Set, then emit it.
                if(tx.get(row, FluoQueryColumns.STATEMENT_PATTERN_BINDING_SET) == null) {
                    try {
                        final Bytes valueBytes = BS_SERDE.serialize(visBindingSet);

                        log.trace("Transaction ID: {}\nMatched Statement Pattern: {}\nBinding Set: {}\n",
                                tx.getStartTimestamp(), spID, visBindingSet);

                        tx.set(row, FluoQueryColumns.STATEMENT_PATTERN_BINDING_SET, valueBytes);
                    } catch(final Exception e) {
                        log.error("Couldn't serialize a Binding Set. This value will be skipped.", e);
                    }
                }
            }
        }

        // Once the triple has been handled, it may be deleted.
        tx.delete(brow, column);
    }

    /**
     * Determines whether a triple matches a Statement Pattern. If so, it generates a string representation of a
     * BindingSet whose order is determined by varOrder.
     *
     * @param triple - The triple to consider.
     * @param pattern - The pattern the triple must match.
     * @param varOrder - The variable order of the Binding Set String that is produced by this method.
     * @return The string representation of a Binding Set that is generated by matching the triple to the pattern;
     *   otherwise an empty string if the pattern couldn't be matched.
     */
    private static String getBindingSet(final String triple, final String pattern, final VariableOrder varOrder) {
        final String[] patternArray = pattern.split(DELIM);
        final String[] tripleArray = triple.split(DELIM);
        final String[] varOrderArray = varOrder.toArray();
        final Map<String,String> bindingValues = Maps.newHashMap();

        if(patternArray.length != 3 || tripleArray.length != 3) {
            throw new IllegalArgumentException("Invald number of components");
        }

        // Extract the binding names and values.
        for(int i = 0; i < 3; i ++) {
            if(patternArray[i].startsWith("-const-")) {
                // If a constant value does not match, then the triple does not match the pattern.
                if(!patternArray[i].substring(7).equals(tripleArray[i])) {
                    return "";
                }
            } else{
                bindingValues.put(patternArray[i], tripleArray[i]);
            }
        }

        // Create the returned binding set string from the extracted values.
        String bindingSetString = "";
        for (final String bindingName : varOrderArray) {
            if(bindingSetString.length() == 0) {
                bindingSetString = bindingValues.get(bindingName);
            } else {
                bindingSetString = bindingSetString + DELIM + bindingValues.get(bindingName);
            }
        }

        return bindingSetString;
    }
}