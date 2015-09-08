/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

import org.apache.lucene.search.Query;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.inject.multibindings.Multibinder;
import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.EnvironmentModule;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNameModule;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.index.cache.IndexCacheModule;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.functionscore.ScoreFunctionParser;
import org.elasticsearch.index.query.support.QueryParsers;
import org.elasticsearch.index.settings.IndexSettingsModule;
import org.elasticsearch.index.similarity.SimilarityModule;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.TestSearchContext;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.test.cluster.TestClusterService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolModule;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.*;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.Matchers.*;

public abstract class BaseQueryTestCase<QB extends AbstractQueryBuilder<QB>> extends ESTestCase {

    protected static final String STRING_FIELD_NAME = "mapped_string";
    protected static final String INT_FIELD_NAME = "mapped_int";
    protected static final String DOUBLE_FIELD_NAME = "mapped_double";
    protected static final String BOOLEAN_FIELD_NAME = "mapped_boolean";
    protected static final String DATE_FIELD_NAME = "mapped_date";
    protected static final String OBJECT_FIELD_NAME = "mapped_object";
    protected static final String[] MAPPED_FIELD_NAMES = new String[] { STRING_FIELD_NAME, INT_FIELD_NAME,
            DOUBLE_FIELD_NAME, BOOLEAN_FIELD_NAME, DATE_FIELD_NAME, OBJECT_FIELD_NAME };
    protected static final String[] MAPPED_LEAF_FIELD_NAMES = new String[] { STRING_FIELD_NAME, INT_FIELD_NAME,
            DOUBLE_FIELD_NAME, BOOLEAN_FIELD_NAME, DATE_FIELD_NAME };

    private static Injector injector;
    private static IndexQueryParserService queryParserService;

    protected static IndexQueryParserService queryParserService() {
        return queryParserService;
    }

    private static Index index;

    protected static Index getIndex() {
        return index;
    }

    private static String[] currentTypes;

    protected static String[] getCurrentTypes() {
        return currentTypes;
    }

    private static NamedWriteableRegistry namedWriteableRegistry;

    /**
     * Setup for the whole base test class.
     * @throws IOException
     */
    @BeforeClass
    public static void init() throws IOException {
        // we have to prefer CURRENT since with the range of versions we support it's rather unlikely to get the current actually.
        Version version = randomBoolean() ? Version.CURRENT : VersionUtils.randomVersionBetween(random(), Version.V_1_0_0, Version.CURRENT);
        Settings settings = Settings.settingsBuilder()
                .put("name", BaseQueryTestCase.class.toString())
                .put("path.home", createTempDir())
                .build();
        Settings indexSettings = Settings.settingsBuilder()
                .put(IndexMetaData.SETTING_VERSION_CREATED, version).build();
        index = new Index(randomAsciiOfLengthBetween(1, 10));
        final TestClusterService clusterService = new TestClusterService();
        clusterService.setState(new ClusterState.Builder(clusterService.state()).metaData(new MetaData.Builder().put(
                new IndexMetaData.Builder(index.name()).settings(indexSettings).numberOfShards(1).numberOfReplicas(0))));
        injector = new ModulesBuilder().add(
                new EnvironmentModule(new Environment(settings)),
                new SettingsModule(settings),
                new ThreadPoolModule(new ThreadPool(settings)),
                new IndicesModule(settings) {
                    @Override
                    public void configure() {
                        // skip services
                        bindQueryParsersExtension();
                    }
                },
                new ScriptModule(settings),
                new IndexSettingsModule(index, indexSettings),
                new IndexCacheModule(indexSettings),
                new AnalysisModule(indexSettings, new IndicesAnalysisService(indexSettings)),
                new SimilarityModule(indexSettings),
                new IndexNameModule(index),
        new AbstractModule() {
                    @Override
                    protected void configure() {
                        Multibinder.newSetBinder(binder(), ScoreFunctionParser.class);
                        bind(ClusterService.class).toProvider(Providers.of(clusterService));
                        bind(CircuitBreakerService.class).to(NoneCircuitBreakerService.class);
                        bind(NamedWriteableRegistry.class).asEagerSingleton();
                    }
                }
        ).createInjector();
        queryParserService = injector.getInstance(IndexQueryParserService.class);
        MapperService mapperService = queryParserService.mapperService;
        //create some random type with some default field, those types will stick around for all of the subclasses
        currentTypes = new String[randomIntBetween(0, 5)];
        for (int i = 0; i < currentTypes.length; i++) {
            String type = randomAsciiOfLengthBetween(1, 10);
            mapperService.merge(type, new CompressedXContent(PutMappingRequest.buildFromSimplifiedDef(type,
                    STRING_FIELD_NAME, "type=string",
                    INT_FIELD_NAME, "type=integer",
                    DOUBLE_FIELD_NAME, "type=double",
                    BOOLEAN_FIELD_NAME, "type=boolean",
                    DATE_FIELD_NAME, "type=date",
                    OBJECT_FIELD_NAME, "type=object"
            ).string()), false, false);
            // also add mappings for two inner field in the object field
            mapperService.merge(type, new CompressedXContent("{\"properties\":{\""+OBJECT_FIELD_NAME+"\":{\"type\":\"object\","
                    + "\"properties\":{\""+DATE_FIELD_NAME+"\":{\"type\":\"date\"},\""+INT_FIELD_NAME+"\":{\"type\":\"integer\"}}}}}"), false, false);
            currentTypes[i] = type;
        }
        namedWriteableRegistry = injector.getInstance(NamedWriteableRegistry.class);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        terminate(injector.getInstance(ThreadPool.class));
        injector = null;
        index = null;
        queryParserService = null;
        currentTypes = null;
        namedWriteableRegistry = null;
    }

    @Before
    public void beforeTest() {
        //set some random types to be queried as part the search request, before each test
        String[] types = getRandomTypes();
        //some query (e.g. range query) have a different behaviour depending on whether the current search context is set or not
        //which is why we randomly set the search context, which will internally also do QueryParseContext.setTypes(types)
        if (randomBoolean()) {
            QueryShardContext.setTypes(types);
        } else {
           setSearchContext(types); // TODO should this be set after we parsed and before we build the query? it makes more sense?
        }
    }

    protected void setSearchContext(String[] types) {
        TestSearchContext testSearchContext = new TestSearchContext();
        testSearchContext.setTypes(types);
        SearchContext.setCurrent(testSearchContext);
    }

    @After
    public void afterTest() {
        QueryShardContext.removeTypes();
        SearchContext.removeCurrent();
    }

    protected final QB createTestQueryBuilder() {
        QB query = doCreateTestQueryBuilder();
        //we should not set boost and query name for queries that don't parse it
        if (supportsBoostAndQueryName()) {
            if (randomBoolean()) {
                query.boost(2.0f / randomIntBetween(1, 20));
            }
            if (randomBoolean()) {
                query.queryName(randomAsciiOfLengthBetween(1, 10));
            }
        }
        return query;
    }

    /**
     * Create the query that is being tested
     */
    protected abstract QB doCreateTestQueryBuilder();

    /**
     * Generic test that creates new query from the test query and checks both for equality
     * and asserts equality on the two queries.
     */
    @Test
    public void testFromXContent() throws IOException {
        QB testQuery = createTestQueryBuilder();
        assertParsedQuery(testQuery.toString(), testQuery);
        for (Map.Entry<String, QB> alternateVersion : getAlternateVersions().entrySet()) {
            assertParsedQuery(alternateVersion.getKey(), alternateVersion.getValue());
        }
    }

    /**
     * Returns alternate string representation of the query that need to be tested as they are never used as output
     * of {@link QueryBuilder#toXContent(XContentBuilder, ToXContent.Params)}. By default there are no alternate versions.
     */
    protected Map<String, QB> getAlternateVersions() {
        return Collections.emptyMap();
    }

    /**
     * Parses the query provided as string argument and compares it with the expected result provided as argument as a {@link QueryBuilder}
     */
    protected void assertParsedQuery(String queryAsString, QueryBuilder<?> expectedQuery) throws IOException {
        QueryBuilder<?> newQuery = parseQuery(queryAsString);
        assertNotSame(newQuery, expectedQuery);
        assertEquals(expectedQuery, newQuery);
        assertEquals(expectedQuery.hashCode(), newQuery.hashCode());
    }

    protected QueryBuilder<?> parseQuery(String queryAsString) throws IOException {
        XContentParser parser = XContentFactory.xContent(queryAsString).createParser(queryAsString);
        QueryParseContext context = createParseContext();
        context.reset(parser);
        return context.parseInnerQueryBuilder();
    }

    /**
     * Test creates the {@link Query} from the {@link QueryBuilder} under test and delegates the
     * assertions being made on the result to the implementing subclass.
     */
    @Test
    public void testToQuery() throws IOException {
        QueryShardContext context = createShardContext();
        context.setAllowUnmappedFields(true);

        QB firstQuery = createTestQueryBuilder();
        Query firstLuceneQuery = firstQuery.toQuery(context);
        assertLuceneQuery(firstQuery, firstLuceneQuery, context);

        QB secondQuery = copyQuery(firstQuery);
        //query _name never should affect the result of toQuery, we randomly set it to make sure
        if (randomBoolean()) {
            secondQuery.queryName(secondQuery.queryName() == null ? randomAsciiOfLengthBetween(1, 30) : secondQuery.queryName() + randomAsciiOfLengthBetween(1, 10));
        }
        Query secondLuceneQuery = secondQuery.toQuery(context);
        assertLuceneQuery(secondQuery, secondLuceneQuery, context);
        assertThat("two equivalent query builders lead to different lucene queries", secondLuceneQuery, equalTo(firstLuceneQuery));

        //if the initial lucene query is null, changing its boost won't have any effect, we shouldn't test that
        if (firstLuceneQuery != null && supportsBoostAndQueryName()) {
            secondQuery.boost(firstQuery.boost() + 1f + randomFloat());
            Query thirdLuceneQuery = secondQuery.toQuery(context);
            assertThat("modifying the boost doesn't affect the corresponding lucene query", firstLuceneQuery, not(equalTo(thirdLuceneQuery)));
        }
    }

    /**
     * Few queries allow you to set the boost and queryName on the java api, although the corresponding parser doesn't parse them as they are not supported.
     * This method allows to disable boost and queryName related tests for those queries. Those queries are easy to identify: their parsers
     * don't parse `boost` and `_name` as they don't apply to the specific query: filter query, wrapper query and match_none
     */
    protected boolean supportsBoostAndQueryName() {
        return true;
    }

    /**
     * Checks the result of {@link QueryBuilder#toQuery(QueryShardContext)} given the original {@link QueryBuilder} and {@link QueryShardContext}.
     * Verifies that named queries and boost are properly handled and delegates to {@link #doAssertLuceneQuery(AbstractQueryBuilder, Query, QueryShardContext)}
     * for query specific checks.
     */
    protected final void assertLuceneQuery(QB queryBuilder, Query query, QueryShardContext context) throws IOException {
        if (queryBuilder.queryName() != null) {
            Query namedQuery = context.copyNamedQueries().get(queryBuilder.queryName());
            assertThat(namedQuery, equalTo(query));
        }
        if (query != null && supportsBoostAndQueryName()) {
            assertBoost(queryBuilder, query);
        }
        doAssertLuceneQuery(queryBuilder, query, context);
    }

    /**
     * Allows to override boost assertions for queries that don't have the default behaviour
     */
    protected void assertBoost(QB queryBuilder, Query query) throws IOException {
        assertThat(query.getBoost(), equalTo(queryBuilder.boost()));
    }

    /**
     * Checks the result of {@link QueryBuilder#toQuery(QueryShardContext)} given the original {@link QueryBuilder} and {@link QueryShardContext}.
     * Contains the query specific checks to be implemented by subclasses.
     */
    protected abstract void doAssertLuceneQuery(QB queryBuilder, Query query, QueryShardContext context) throws IOException;

    /**
     * Test serialization and deserialization of the test query.
     */
    @Test
    public void testSerialization() throws IOException {
        QB testQuery = createTestQueryBuilder();
        try (BytesStreamOutput output = new BytesStreamOutput()) {
            testQuery.writeTo(output);
            try (StreamInput in = new NamedWriteableAwareStreamInput(StreamInput.wrap(output.bytes()), namedWriteableRegistry)) {
                QueryBuilder<?> prototype = queryParser(testQuery.getName()).getBuilderPrototype();
                QueryBuilder deserializedQuery = prototype.readFrom(in);
                assertEquals(deserializedQuery, testQuery);
                assertEquals(deserializedQuery.hashCode(), testQuery.hashCode());
                assertNotSame(deserializedQuery, testQuery);
            }
        }
    }

    @Test
    public void testEqualsAndHashcode() throws IOException {
        QB firstQuery = createTestQueryBuilder();
        assertFalse("query is equal to null", firstQuery.equals(null));
        assertFalse("query is equal to incompatible type", firstQuery.equals(""));
        assertTrue("query is not equal to self", firstQuery.equals(firstQuery));
        assertThat("same query's hashcode returns different values if called multiple times", firstQuery.hashCode(), equalTo(firstQuery.hashCode()));

        QB secondQuery = copyQuery(firstQuery);
        assertTrue("query is not equal to self", secondQuery.equals(secondQuery));
        assertTrue("query is not equal to its copy", firstQuery.equals(secondQuery));
        assertTrue("equals is not symmetric", secondQuery.equals(firstQuery));
        assertThat("query copy's hashcode is different from original hashcode", secondQuery.hashCode(), equalTo(firstQuery.hashCode()));

        QB thirdQuery = copyQuery(secondQuery);
        assertTrue("query is not equal to self", thirdQuery.equals(thirdQuery));
        assertTrue("query is not equal to its copy", secondQuery.equals(thirdQuery));
        assertThat("query copy's hashcode is different from original hashcode", secondQuery.hashCode(), equalTo(thirdQuery.hashCode()));
        assertTrue("equals is not transitive", firstQuery.equals(thirdQuery));
        assertThat("query copy's hashcode is different from original hashcode", firstQuery.hashCode(), equalTo(thirdQuery.hashCode()));
        assertTrue("equals is not symmetric", thirdQuery.equals(secondQuery));
        assertTrue("equals is not symmetric", thirdQuery.equals(firstQuery));

        if (randomBoolean()) {
            secondQuery.queryName(secondQuery.queryName() == null ? randomAsciiOfLengthBetween(1, 30) : secondQuery.queryName() + randomAsciiOfLengthBetween(1, 10));
        } else {
            secondQuery.boost(firstQuery.boost() + 1f + randomFloat());
        }
        assertThat("different queries should not be equal", secondQuery, not(equalTo(firstQuery)));
        assertThat("different queries should have different hashcode", secondQuery.hashCode(), not(equalTo(firstQuery.hashCode())));
    }

    private QueryParser<?> queryParser(String queryId) {
        return queryParserService.indicesQueriesRegistry().queryParsers().get(queryId);
    }

    //we use the streaming infra to create a copy of the query provided as argument
    protected QB copyQuery(QB query) throws IOException {
        try (BytesStreamOutput output = new BytesStreamOutput()) {
            query.writeTo(output);
            try (StreamInput in = new NamedWriteableAwareStreamInput(StreamInput.wrap(output.bytes()), namedWriteableRegistry)) {
                QueryBuilder<?> prototype = queryParser(query.getName()).getBuilderPrototype();
                @SuppressWarnings("unchecked")
                QB secondQuery = (QB)prototype.readFrom(in);
                return secondQuery;
            }
        }
    }

    /**
     * @return a new {@link QueryShardContext} based on the base test index and queryParserService
     */
    protected static QueryShardContext createShardContext() {
        QueryShardContext queryCreationContext = new QueryShardContext(index, queryParserService);
        queryCreationContext.parseFieldMatcher(ParseFieldMatcher.EMPTY);
        return queryCreationContext;
    }

    /**
     * @return a new {@link QueryParseContext} based on the base test index and queryParserService
     */
    protected static QueryParseContext createParseContext() {
        return createShardContext().parseContext();
    }

    protected static void assertValidate(QueryBuilder queryBuilder, int totalExpectedErrors) {
        QueryValidationException queryValidationException = queryBuilder.validate();
        if (totalExpectedErrors > 0) {
            assertThat(queryValidationException, notNullValue());
            assertThat(queryValidationException.validationErrors().size(), equalTo(totalExpectedErrors));
        } else {
            assertThat(queryValidationException, nullValue());
        }
    }

    /**
     * create a random value for either {@link BaseQueryTestCase#BOOLEAN_FIELD_NAME}, {@link BaseQueryTestCase#INT_FIELD_NAME},
     * {@link BaseQueryTestCase#DOUBLE_FIELD_NAME}, {@link BaseQueryTestCase#STRING_FIELD_NAME} or
     * {@link BaseQueryTestCase#DATE_FIELD_NAME}, or a String value by default
     */
    protected static Object getRandomValueForFieldName(String fieldName) {
        Object value;
        switch (fieldName) {
            case STRING_FIELD_NAME:
                value = rarely() ? randomUnicodeOfLength(10) : randomAsciiOfLengthBetween(1, 10); // unicode in 10% cases
                break;
            case INT_FIELD_NAME:
                value = randomIntBetween(0, 10);
                break;
            case DOUBLE_FIELD_NAME:
                value = randomDouble() * 10;
                break;
            case BOOLEAN_FIELD_NAME:
                value = randomBoolean();
                break;
            case DATE_FIELD_NAME:
                value = new DateTime(System.currentTimeMillis(), DateTimeZone.UTC).toString();
                break;
            default:
                value = randomAsciiOfLengthBetween(1, 10);
        }
        return value;
    }

    /**
     * Helper method to return a mapped or a random field
     */
    protected String getRandomFieldName() {
        // if no type is set then return a random field name
        if (currentTypes == null || currentTypes.length == 0 || randomBoolean()) {
            return randomAsciiOfLengthBetween(1, 10);
        }
        return randomFrom(MAPPED_LEAF_FIELD_NAMES);
    }

    /**
     * Helper method to return a random field (mapped or unmapped) and a value
     */
    protected Tuple<String, Object> getRandomFieldNameAndValue() {
        String fieldName = getRandomFieldName();
        return new Tuple<>(fieldName, getRandomValueForFieldName(fieldName));
    }

    /**
     * Helper method to return a random rewrite method
     */
    protected static String getRandomRewriteMethod() {
        String rewrite;
        if (randomBoolean()) {
            rewrite = randomFrom(QueryParsers.CONSTANT_SCORE,
                    QueryParsers.SCORING_BOOLEAN,
                    QueryParsers.CONSTANT_SCORE_BOOLEAN).getPreferredName();
        } else {
            rewrite = randomFrom(QueryParsers.TOP_TERMS,
                    QueryParsers.TOP_TERMS_BOOST,
                    QueryParsers.TOP_TERMS_BLENDED_FREQS).getPreferredName() + "1";
        }
        return rewrite;
    }

    protected String[] getRandomTypes() {
        String[] types;
        if (currentTypes.length > 0 && randomBoolean()) {
            int numberOfQueryTypes = randomIntBetween(1, currentTypes.length);
            types = new String[numberOfQueryTypes];
            for (int i = 0; i < numberOfQueryTypes; i++) {
                types[i] = randomFrom(currentTypes);
            }
        } else {
            if (randomBoolean()) {
                types = new String[] { MetaData.ALL };
            } else {
                types = new String[0];
            }
        }
        return types;
    }

    protected String getRandomType() {
        return (currentTypes.length == 0) ? MetaData.ALL : randomFrom(currentTypes);
    }

    protected static Fuzziness randomFuzziness(String fieldName) {
        Fuzziness fuzziness = Fuzziness.AUTO;
        switch (fieldName) {
            case INT_FIELD_NAME:
                fuzziness = Fuzziness.build(randomIntBetween(3, 100));
                break;
            case DOUBLE_FIELD_NAME:
                fuzziness = Fuzziness.build(1 + randomFloat() * 10);
                break;
            case DATE_FIELD_NAME:
                fuzziness = Fuzziness.build(randomTimeValue());
                break;
        }
        if (randomBoolean()) {
            fuzziness = Fuzziness.fromEdits(randomIntBetween(0, 2));
        }
        return fuzziness;
    }

    protected static boolean isNumericFieldName(String fieldName) {
        return INT_FIELD_NAME.equals(fieldName) || DOUBLE_FIELD_NAME.equals(fieldName);
    }
}
