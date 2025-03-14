/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.integrations.source.jdbc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.resources.MoreResources;
import io.airbyte.commons.util.MoreIterators;
import io.airbyte.db.Databases;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.source.jdbc.AbstractJdbcSource;
import io.airbyte.integrations.source.jdbc.models.JdbcState;
import io.airbyte.integrations.source.jdbc.models.JdbcStreamState;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.AirbyteConnectionStatus;
import io.airbyte.protocol.models.AirbyteConnectionStatus.Status;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.AirbyteStateMessage;
import io.airbyte.protocol.models.AirbyteStream;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import io.airbyte.protocol.models.ConnectorSpecification;
import io.airbyte.protocol.models.DestinationSyncMode;
import io.airbyte.protocol.models.Field;
import io.airbyte.protocol.models.Field.JsonSchemaPrimitive;
import io.airbyte.protocol.models.SyncMode;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tests that should be run on all Sources that extend the AbstractJdbcSource.
 */
// How leverage these tests:
// 1. Extend this class in the test module of the Source.
// 2. From the class that extends this one, you MUST call super.setup() in a @BeforeEach method.
// Otherwise you'll see many NPE issues. Your before each should also handle providing a fresh
// database between each test.
// 3. From the class that extends this one, implement a @AfterEach that cleans out the database
// between each test.
// 4. Then implement the abstract methods documented below.
public abstract class JdbcSourceStandardTest {

  public static String SCHEMA_NAME = "jdbc_integration_test1";
  public static String SCHEMA_NAME2 = "jdbc_integration_test2";
  public static Set<String> TEST_SCHEMAS = ImmutableSet.of(SCHEMA_NAME, SCHEMA_NAME2);

  public static String TABLE_NAME = "id_and_name";
  public static String TABLE_NAME_WITH_SPACES = "id and name";
  public static String TABLE_NAME_WITHOUT_PK = "id_and_name_without_pk";
  public static String TABLE_NAME_COMPOSITE_PK = "full_name_composite_pk";

  public static String COL_ID = "id";
  public static String COL_NAME = "name";
  public static String COL_UPDATED_AT = "updated_at";
  public static String COL_FIRST_NAME = "first_name";
  public static String COL_LAST_NAME = "last_name";
  public static String COL_LAST_NAME_WITH_SPACE = "last name";
  public static Number ID_VALUE_1 = 1;
  public static Number ID_VALUE_2 = 2;
  public static Number ID_VALUE_3 = 3;
  public static Number ID_VALUE_4 = 4;
  public static Number ID_VALUE_5 = 5;

  public JsonNode config;
  public JdbcDatabase database;
  public AbstractJdbcSource source;
  public static String streamName;

  /**
   * These tests write records without specifying a namespace (schema name). They will be written into
   * whatever the default schema is for the database. When they are discovered they will be namespaced
   * by the schema name (e.g. <default-schema-name>.<table_name>). Thus the source needs to tell the
   * tests what that default schema name is. If the database does not support schemas, then database
   * name should used instead.
   *
   * @return name that will be used to namespace the record.
   */
  public abstract boolean supportsSchemas();

  /**
   * A valid configuration to connect to a test database.
   *
   * @return config
   */
  public abstract JsonNode getConfig();

  /**
   * Full qualified class name of the JDBC driver for the database.
   *
   * @return driver
   */
  public abstract String getDriverClass();

  /**
   * An instance of the source that should be tests.
   *
   * @return source
   */
  public abstract AbstractJdbcSource getSource();

  public void setup() throws Exception {
    source = getSource();
    config = getConfig();
    final JsonNode jdbcConfig = source.toJdbcConfig(config);

    streamName = TABLE_NAME;

    database = Databases.createJdbcDatabase(
        jdbcConfig.get("username").asText(),
        jdbcConfig.has("password") ? jdbcConfig.get("password").asText() : null,
        jdbcConfig.get("jdbc_url").asText(),
        getDriverClass());

    if (supportsSchemas()) {
      createSchemas();
    }

    if (getDriverClass().toLowerCase().contains("oracle")) {
      database.execute(connection -> connection.createStatement()
          .execute("ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD'"));
    }

    database.execute(connection -> {

      connection.createStatement().execute(
          String.format(
              "CREATE TABLE %s(id INTEGER, name VARCHAR(200), updated_at DATE, PRIMARY KEY (id))",
              getFullyQualifiedTableName(TABLE_NAME)));
      connection.createStatement().execute(
          String.format("INSERT INTO %s(id, name, updated_at) VALUES (1,'picard', '2004-10-19')",
              getFullyQualifiedTableName(TABLE_NAME)));
      connection.createStatement().execute(
          String.format("INSERT INTO %s(id, name, updated_at) VALUES (2, 'crusher', '2005-10-19')",
              getFullyQualifiedTableName(TABLE_NAME)));
      connection.createStatement().execute(
          String.format("INSERT INTO %s(id, name, updated_at) VALUES (3, 'vash', '2006-10-19')",
              getFullyQualifiedTableName(TABLE_NAME)));

      connection.createStatement().execute(
          String.format("CREATE TABLE %s(id INTEGER, name VARCHAR(200), updated_at DATE)",
              getFullyQualifiedTableName(TABLE_NAME_WITHOUT_PK)));
      connection.createStatement().execute(
          String.format("INSERT INTO %s(id, name, updated_at) VALUES (1,'picard', '2004-10-19')",
              getFullyQualifiedTableName(TABLE_NAME_WITHOUT_PK)));
      connection.createStatement().execute(
          String.format("INSERT INTO %s(id, name, updated_at) VALUES (2, 'crusher', '2005-10-19')",
              getFullyQualifiedTableName(TABLE_NAME_WITHOUT_PK)));
      connection.createStatement().execute(
          String.format("INSERT INTO %s(id, name, updated_at) VALUES (3, 'vash', '2006-10-19')",
              getFullyQualifiedTableName(TABLE_NAME_WITHOUT_PK)));

      connection.createStatement().execute(
          String.format(
              "CREATE TABLE %s(first_name VARCHAR(200), last_name VARCHAR(200), updated_at DATE, PRIMARY KEY (first_name, last_name))",
              getFullyQualifiedTableName(TABLE_NAME_COMPOSITE_PK)));
      connection.createStatement().execute(
          String.format(
              "INSERT INTO %s(first_name, last_name, updated_at) VALUES ('first' ,'picard', '2004-10-19')",
              getFullyQualifiedTableName(TABLE_NAME_COMPOSITE_PK)));
      connection.createStatement().execute(
          String.format(
              "INSERT INTO %s(first_name, last_name, updated_at) VALUES ('second', 'crusher', '2005-10-19')",
              getFullyQualifiedTableName(TABLE_NAME_COMPOSITE_PK)));
      connection.createStatement().execute(
          String.format(
              "INSERT INTO %s(first_name, last_name, updated_at) VALUES  ('third', 'vash', '2006-10-19')",
              getFullyQualifiedTableName(TABLE_NAME_COMPOSITE_PK)));

    });
  }

  public void tearDown() throws SQLException {
    dropSchemas();
  }

  @Test
  void testSpec() throws Exception {
    final ConnectorSpecification actual = source.spec();
    final String resourceString = MoreResources.readResource("spec.json");
    final ConnectorSpecification expected = Jsons.deserialize(resourceString, ConnectorSpecification.class);

    assertEquals(expected, actual);
  }

  @Test
  void testCheckSuccess() throws Exception {
    final AirbyteConnectionStatus actual = source.check(config);
    final AirbyteConnectionStatus expected = new AirbyteConnectionStatus().withStatus(Status.SUCCEEDED);
    assertEquals(expected, actual);
  }

  @Test
  void testCheckFailure() throws Exception {
    ((ObjectNode) config).put("password", "fake");
    final AirbyteConnectionStatus actual = source.check(config);
    assertEquals(Status.FAILED, actual.getStatus());
  }

  @Test
  void testDiscover() throws Exception {
    final AirbyteCatalog actual = filterOutOtherSchemas(source.discover(config));
    AirbyteCatalog expected = getCatalog(getDefaultNamespace());
    assertEquals(expected.getStreams().size(), actual.getStreams().size());
    actual.getStreams().forEach(actualStream -> {
      final Optional<AirbyteStream> expectedStream =
          expected.getStreams().stream()
              .filter(stream -> stream.getNamespace().equals(actualStream.getNamespace()) && stream.getName().equals(actualStream.getName()))
              .findAny();
      assertTrue(expectedStream.isPresent(), String.format("Unexpected stream %s", actualStream.getName()));
      assertEquals(expectedStream.get(), actualStream);
    });
  }

  private AirbyteCatalog filterOutOtherSchemas(AirbyteCatalog catalog) {
    if (supportsSchemas()) {
      final AirbyteCatalog filteredCatalog = Jsons.clone(catalog);
      filteredCatalog.setStreams(filteredCatalog.getStreams()
          .stream()
          .filter(stream -> TEST_SCHEMAS.stream().anyMatch(schemaName -> stream.getNamespace().startsWith(schemaName)))
          .collect(Collectors.toList()));
      return filteredCatalog;
    } else {
      return catalog;
    }

  }

  @Test
  void testDiscoverWithMultipleSchemas() throws Exception {
    // mysql does not have a concept of schemas, so this test does not make sense for it.
    if (getDriverClass().toLowerCase().contains("mysql")) {
      return;
    }

    // add table and data to a separate schema.
    database.execute(connection -> {
      connection.createStatement().execute(
          String.format("CREATE TABLE %s(id VARCHAR(200), name VARCHAR(200))",
              JdbcUtils.getFullyQualifiedTableName(SCHEMA_NAME2, TABLE_NAME)));
      connection.createStatement()
          .execute(String.format("INSERT INTO %s(id, name) VALUES ('1','picard')",
              JdbcUtils.getFullyQualifiedTableName(SCHEMA_NAME2, TABLE_NAME)));
      connection.createStatement()
          .execute(String.format("INSERT INTO %s(id, name) VALUES ('2', 'crusher')",
              JdbcUtils.getFullyQualifiedTableName(SCHEMA_NAME2, TABLE_NAME)));
      connection.createStatement()
          .execute(String.format("INSERT INTO %s(id, name) VALUES ('3', 'vash')",
              JdbcUtils.getFullyQualifiedTableName(SCHEMA_NAME2, TABLE_NAME)));
    });

    final AirbyteCatalog actual = source.discover(config);

    final AirbyteCatalog expected = getCatalog(getDefaultNamespace());
    expected.getStreams().add(CatalogHelpers
        .createAirbyteStream(TABLE_NAME,
            SCHEMA_NAME2,
            Field.of(COL_ID, JsonSchemaPrimitive.STRING),
            Field.of(COL_NAME, JsonSchemaPrimitive.STRING))
        .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL)));
    // sort streams by name so that we are comparing lists with the same order.
    Comparator<AirbyteStream> schemaTableCompare = Comparator.comparing(stream -> stream.getNamespace() + "." + stream.getName());
    expected.getStreams().sort(schemaTableCompare);
    actual.getStreams().sort(schemaTableCompare);
    assertEquals(expected, filterOutOtherSchemas(actual));
  }

  @Test
  void testReadSuccess() throws Exception {
    final List<AirbyteMessage> actualMessages =
        MoreIterators.toList(
            source.read(config, getConfiguredCatalogWithOneStream(getDefaultNamespace()), null));

    setEmittedAtToNull(actualMessages);

    assertEquals(getTestMessages(), actualMessages);
  }

  @Test
  void testReadOneColumn() throws Exception {
    final ConfiguredAirbyteCatalog catalog = CatalogHelpers
        .createConfiguredAirbyteCatalog(streamName, getDefaultNamespace(), Field.of(COL_ID, JsonSchemaPrimitive.NUMBER));
    final List<AirbyteMessage> actualMessages = MoreIterators
        .toList(source.read(config, catalog, null));

    setEmittedAtToNull(actualMessages);

    final List<AirbyteMessage> expectedMessages = getTestMessages().stream()
        .map(Jsons::clone)
        .peek(m -> {
          ((ObjectNode) m.getRecord().getData()).remove(COL_NAME);
          ((ObjectNode) m.getRecord().getData()).remove(COL_UPDATED_AT);
          ((ObjectNode) m.getRecord().getData()).replace(COL_ID,
              convertIdBasedOnDatabase(m.getRecord().getData().get(COL_ID).asInt()));
        })
        .collect(Collectors.toList());
    assertEquals(expectedMessages, actualMessages);
  }

  @Test
  void testReadMultipleTables() throws Exception {
    final ConfiguredAirbyteCatalog catalog = getConfiguredCatalogWithOneStream(
        getDefaultNamespace());
    final List<AirbyteMessage> expectedMessages = new ArrayList<>(getTestMessages());

    for (int i = 2; i < 10; i++) {
      final int iFinal = i;
      final String streamName2 = streamName + i;
      database.execute(connection -> {
        connection.createStatement()
            .execute(String.format("CREATE TABLE %s(id INTEGER, name VARCHAR(200))",
                getFullyQualifiedTableName(TABLE_NAME + iFinal)));
        connection.createStatement()
            .execute(String.format("INSERT INTO %s(id, name) VALUES (1,'picard')",
                getFullyQualifiedTableName(TABLE_NAME + iFinal)));
        connection.createStatement()
            .execute(String.format("INSERT INTO %s(id, name) VALUES (2, 'crusher')",
                getFullyQualifiedTableName(TABLE_NAME + iFinal)));
        connection.createStatement()
            .execute(String.format("INSERT INTO %s(id, name) VALUES (3, 'vash')",
                getFullyQualifiedTableName(TABLE_NAME + iFinal)));
      });
      catalog.getStreams().add(CatalogHelpers.createConfiguredAirbyteStream(
          streamName2,
          getDefaultNamespace(),
          Field.of(COL_ID, JsonSchemaPrimitive.NUMBER),
          Field.of(COL_NAME, JsonSchemaPrimitive.STRING)));

      final List<AirbyteMessage> secondStreamExpectedMessages = getTestMessages()
          .stream()
          .map(Jsons::clone)
          .peek(m -> {
            m.getRecord().setStream(streamName2);
            m.getRecord().setNamespace(getDefaultNamespace());
            ((ObjectNode) m.getRecord().getData()).remove(COL_UPDATED_AT);
            ((ObjectNode) m.getRecord().getData()).replace(COL_ID,
                convertIdBasedOnDatabase(m.getRecord().getData().get(COL_ID).asInt()));
          })
          .collect(Collectors.toList());
      expectedMessages.addAll(secondStreamExpectedMessages);
    }

    final List<AirbyteMessage> actualMessages = MoreIterators
        .toList(source.read(config, catalog, null));

    setEmittedAtToNull(actualMessages);

    assertEquals(expectedMessages, actualMessages);
  }

  @Test
  void testTablesWithQuoting() throws Exception {
    final ConfiguredAirbyteStream streamForTableWithSpaces = createTableWithSpaces();

    final ConfiguredAirbyteCatalog catalog = new ConfiguredAirbyteCatalog()
        .withStreams(Lists.newArrayList(
            getConfiguredCatalogWithOneStream(getDefaultNamespace()).getStreams().get(0),
            streamForTableWithSpaces));
    final List<AirbyteMessage> actualMessages = MoreIterators
        .toList(source.read(config, catalog, null));

    setEmittedAtToNull(actualMessages);

    final List<AirbyteMessage> secondStreamExpectedMessages = getTestMessages()
        .stream()
        .map(Jsons::clone)
        .peek(m -> {
          m.getRecord().setStream(streamForTableWithSpaces.getStream().getName());
          ((ObjectNode) m.getRecord().getData()).set(COL_LAST_NAME_WITH_SPACE,
              ((ObjectNode) m.getRecord().getData()).remove(COL_NAME));
          ((ObjectNode) m.getRecord().getData()).remove(COL_UPDATED_AT);
          ((ObjectNode) m.getRecord().getData()).replace(COL_ID,
              convertIdBasedOnDatabase(m.getRecord().getData().get(COL_ID).asInt()));
        })
        .collect(Collectors.toList());
    final List<AirbyteMessage> expectedMessages = new ArrayList<>(getTestMessages());
    expectedMessages.addAll(secondStreamExpectedMessages);

    assertEquals(expectedMessages, actualMessages);
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  void testReadFailure() {
    final ConfiguredAirbyteStream spiedAbStream = spy(
        getConfiguredCatalogWithOneStream(getDefaultNamespace()).getStreams().get(0));
    final ConfiguredAirbyteCatalog catalog = new ConfiguredAirbyteCatalog()
        .withStreams(Lists.newArrayList(spiedAbStream));
    doCallRealMethod().doThrow(new RuntimeException()).when(spiedAbStream).getStream();

    assertThrows(RuntimeException.class, () -> source.read(config, catalog, null));
  }

  @Test
  void testIncrementalNoPreviousState() throws Exception {
    incrementalCursorCheck(
        COL_ID,
        null,
        "3",
        Lists.newArrayList(getTestMessages()));
  }

  @Test
  void testIncrementalIntCheckCursor() throws Exception {
    incrementalCursorCheck(
        COL_ID,
        "2",
        "3",
        Lists.newArrayList(getTestMessages().get(2)));
  }

  @Test
  void testIncrementalStringCheckCursor() throws Exception {
    incrementalCursorCheck(
        COL_NAME,
        "patent",
        "vash",
        Lists.newArrayList(getTestMessages().get(0), getTestMessages().get(2)));
  }

  @Test
  void testIncrementalStringCheckCursorSpaceInColumnName() throws Exception {
    final ConfiguredAirbyteStream streamWithSpaces = createTableWithSpaces();

    final AirbyteMessage firstMessage = getTestMessages().get(0);
    firstMessage.getRecord().setStream(streamWithSpaces.getStream().getName());
    ((ObjectNode) firstMessage.getRecord().getData()).remove(COL_UPDATED_AT);
    ((ObjectNode) firstMessage.getRecord().getData()).set(COL_LAST_NAME_WITH_SPACE,
        ((ObjectNode) firstMessage.getRecord().getData()).remove(COL_NAME));

    final AirbyteMessage secondMessage = getTestMessages().get(2);
    secondMessage.getRecord().setStream(streamWithSpaces.getStream().getName());
    ((ObjectNode) secondMessage.getRecord().getData()).remove(COL_UPDATED_AT);
    ((ObjectNode) secondMessage.getRecord().getData()).set(COL_LAST_NAME_WITH_SPACE,
        ((ObjectNode) secondMessage.getRecord().getData()).remove(COL_NAME));

    Lists.newArrayList(getTestMessages().get(0), getTestMessages().get(2));

    incrementalCursorCheck(
        COL_LAST_NAME_WITH_SPACE,
        COL_LAST_NAME_WITH_SPACE,
        "patent",
        "vash",
        Lists.newArrayList(firstMessage, secondMessage),
        streamWithSpaces);
  }

  @Test
  void testIncrementalTimestampCheckCursor() throws Exception {
    incrementalCursorCheck(
        COL_UPDATED_AT,
        "2005-10-18T00:00:00Z",
        "2006-10-19T00:00:00Z",
        Lists.newArrayList(getTestMessages().get(1), getTestMessages().get(2)));
  }

  @Test
  void testIncrementalCursorChanges() throws Exception {
    incrementalCursorCheck(
        COL_ID,
        COL_NAME,
        // cheesing this value a little bit. in the correct implementation this initial cursor value should
        // be ignored because the cursor field changed. setting it to a value that if used, will cause
        // records to (incorrectly) be filtered out.
        "data",
        "vash",
        Lists.newArrayList(getTestMessages()));
  }

  @Test
  void testReadOneTableIncrementallyTwice() throws Exception {
    final String namespace = getDefaultNamespace();
    final ConfiguredAirbyteCatalog configuredCatalog = getConfiguredCatalogWithOneStream(namespace);
    configuredCatalog.getStreams().forEach(airbyteStream -> {
      airbyteStream.setSyncMode(SyncMode.INCREMENTAL);
      airbyteStream.setCursorField(Lists.newArrayList(COL_ID));
      airbyteStream.setDestinationSyncMode(DestinationSyncMode.APPEND);
    });

    final JdbcState state = new JdbcState()
        .withStreams(Lists.newArrayList(new JdbcStreamState().withStreamName(streamName).withStreamNamespace(namespace)));
    final List<AirbyteMessage> actualMessagesFirstSync = MoreIterators
        .toList(source.read(config, configuredCatalog, Jsons.jsonNode(state)));

    final Optional<AirbyteMessage> stateAfterFirstSyncOptional = actualMessagesFirstSync.stream()
        .filter(r -> r.getType() == Type.STATE).findFirst();
    assertTrue(stateAfterFirstSyncOptional.isPresent());

    database.execute(connection -> {
      connection.createStatement().execute(
          String.format("INSERT INTO %s(id, name, updated_at) VALUES (4,'riker', '2006-10-19')",
              getFullyQualifiedTableName(TABLE_NAME)));
      connection.createStatement().execute(
          String.format("INSERT INTO %s(id, name, updated_at) VALUES (5, 'data', '2006-10-19')",
              getFullyQualifiedTableName(TABLE_NAME)));
    });

    final List<AirbyteMessage> actualMessagesSecondSync = MoreIterators
        .toList(source.read(config, configuredCatalog,
            stateAfterFirstSyncOptional.get().getState().getData()));

    assertEquals(2,
        (int) actualMessagesSecondSync.stream().filter(r -> r.getType() == Type.RECORD).count());
    final List<AirbyteMessage> expectedMessages = new ArrayList<>();
    expectedMessages.add(new AirbyteMessage().withType(Type.RECORD)
        .withRecord(new AirbyteRecordMessage().withStream(streamName).withNamespace(namespace)
            .withData(Jsons.jsonNode(ImmutableMap
                .of(COL_ID, ID_VALUE_4,
                    COL_NAME, "riker",
                    COL_UPDATED_AT, "2006-10-19T00:00:00Z")))));
    expectedMessages.add(new AirbyteMessage().withType(Type.RECORD)
        .withRecord(new AirbyteRecordMessage().withStream(streamName).withNamespace(namespace)
            .withData(Jsons.jsonNode(ImmutableMap
                .of(COL_ID, ID_VALUE_5,
                    COL_NAME, "data",
                    COL_UPDATED_AT, "2006-10-19T00:00:00Z")))));
    expectedMessages.add(new AirbyteMessage()
        .withType(Type.STATE)
        .withState(new AirbyteStateMessage()
            .withData(Jsons.jsonNode(new JdbcState()
                .withCdc(false)
                .withStreams(Lists.newArrayList(new JdbcStreamState()
                    .withStreamName(streamName)
                    .withStreamNamespace(namespace)
                    .withCursorField(ImmutableList.of(COL_ID))
                    .withCursor("5")))))));

    setEmittedAtToNull(actualMessagesSecondSync);

    assertEquals(expectedMessages, actualMessagesSecondSync);
  }

  @Test
  void testReadMultipleTablesIncrementally() throws Exception {
    final String tableName2 = TABLE_NAME + 2;
    String streamName2 = streamName + 2;
    database.execute(ctx -> {
      ctx.createStatement().execute(String.format("CREATE TABLE %s(id INTEGER, name VARCHAR(200))",
          getFullyQualifiedTableName(tableName2)));
      ctx.createStatement().execute(
          String.format("INSERT INTO %s(id, name) VALUES (1,'picard')",
              getFullyQualifiedTableName(tableName2)));
      ctx.createStatement().execute(
          String.format("INSERT INTO %s(id, name) VALUES (2, 'crusher')",
              getFullyQualifiedTableName(tableName2)));
      ctx.createStatement().execute(
          String.format("INSERT INTO %s(id, name) VALUES (3, 'vash')",
              getFullyQualifiedTableName(tableName2)));
    });

    final String namespace = getDefaultNamespace();
    final ConfiguredAirbyteCatalog configuredCatalog = getConfiguredCatalogWithOneStream(
        namespace);
    configuredCatalog.getStreams().add(CatalogHelpers.createConfiguredAirbyteStream(
        streamName2,
        namespace,
        Field.of(COL_ID, JsonSchemaPrimitive.NUMBER),
        Field.of(COL_NAME, JsonSchemaPrimitive.STRING)));
    configuredCatalog.getStreams().forEach(airbyteStream -> {
      airbyteStream.setSyncMode(SyncMode.INCREMENTAL);
      airbyteStream.setCursorField(Lists.newArrayList(COL_ID));
      airbyteStream.setDestinationSyncMode(DestinationSyncMode.APPEND);
    });

    final JdbcState state = new JdbcState()
        .withStreams(Lists.newArrayList(new JdbcStreamState().withStreamName(streamName).withStreamNamespace(namespace)));
    final List<AirbyteMessage> actualMessagesFirstSync = MoreIterators
        .toList(source.read(config, configuredCatalog, Jsons.jsonNode(state)));

    // get last state message.
    final Optional<AirbyteMessage> stateAfterFirstSyncOptional = actualMessagesFirstSync.stream()
        .filter(r -> r.getType() == Type.STATE)
        .reduce((first, second) -> second);
    assertTrue(stateAfterFirstSyncOptional.isPresent());

    // we know the second streams messages are the same as the first minus the updated at column. so we
    // cheat and generate the expected messages off of the first expected messages.
    final List<AirbyteMessage> secondStreamExpectedMessages = getTestMessages()
        .stream()
        .map(Jsons::clone)
        .peek(m -> {
          m.getRecord().setStream(streamName2);
          ((ObjectNode) m.getRecord().getData()).remove(COL_UPDATED_AT);
          ((ObjectNode) m.getRecord().getData()).replace(COL_ID,
              convertIdBasedOnDatabase(m.getRecord().getData().get(COL_ID).asInt()));
        })
        .collect(Collectors.toList());
    final List<AirbyteMessage> expectedMessagesFirstSync = new ArrayList<>(getTestMessages());
    expectedMessagesFirstSync.add(new AirbyteMessage()
        .withType(Type.STATE)
        .withState(new AirbyteStateMessage()
            .withData(Jsons.jsonNode(new JdbcState()
                .withCdc(false)
                .withStreams(Lists.newArrayList(
                    new JdbcStreamState()
                        .withStreamName(streamName)
                        .withStreamNamespace(namespace)
                        .withCursorField(ImmutableList.of(COL_ID))
                        .withCursor("3"),
                    new JdbcStreamState()
                        .withStreamName(streamName2)
                        .withStreamNamespace(namespace)
                        .withCursorField(ImmutableList.of(COL_ID))))))));

    expectedMessagesFirstSync.addAll(secondStreamExpectedMessages);
    expectedMessagesFirstSync.add(new AirbyteMessage()
        .withType(Type.STATE)
        .withState(new AirbyteStateMessage()
            .withData(Jsons.jsonNode(new JdbcState()
                .withCdc(false)
                .withStreams(Lists.newArrayList(
                    new JdbcStreamState()
                        .withStreamName(streamName)
                        .withStreamNamespace(namespace)
                        .withCursorField(ImmutableList.of(COL_ID))
                        .withCursor("3"),
                    new JdbcStreamState()
                        .withStreamName(streamName2)
                        .withStreamNamespace(namespace)
                        .withCursorField(ImmutableList.of(COL_ID))
                        .withCursor("3")))))));

    setEmittedAtToNull(actualMessagesFirstSync);

    assertEquals(expectedMessagesFirstSync, actualMessagesFirstSync);
  }

  // when initial and final cursor fields are the same.
  private void incrementalCursorCheck(
                                      String cursorField,
                                      String initialCursorValue,
                                      String endCursorValue,
                                      List<AirbyteMessage> expectedRecordMessages)
      throws Exception {
    incrementalCursorCheck(cursorField, cursorField, initialCursorValue, endCursorValue,
        expectedRecordMessages);
  }

  private void incrementalCursorCheck(
                                      String initialCursorField,
                                      String cursorField,
                                      String initialCursorValue,
                                      String endCursorValue,
                                      List<AirbyteMessage> expectedRecordMessages)
      throws Exception {
    incrementalCursorCheck(initialCursorField, cursorField, initialCursorValue, endCursorValue,
        expectedRecordMessages,
        getConfiguredCatalogWithOneStream(getDefaultNamespace()).getStreams().get(0));
  }

  private void incrementalCursorCheck(
                                      String initialCursorField,
                                      String cursorField,
                                      String initialCursorValue,
                                      String endCursorValue,
                                      List<AirbyteMessage> expectedRecordMessages,
                                      ConfiguredAirbyteStream airbyteStream)
      throws Exception {
    airbyteStream.setSyncMode(SyncMode.INCREMENTAL);
    airbyteStream.setCursorField(Lists.newArrayList(cursorField));
    airbyteStream.setDestinationSyncMode(DestinationSyncMode.APPEND);

    final JdbcState state = new JdbcState()
        .withStreams(Lists.newArrayList(new JdbcStreamState()
            .withStreamName(airbyteStream.getStream().getName())
            .withStreamNamespace(airbyteStream.getStream().getNamespace())
            .withCursorField(ImmutableList.of(initialCursorField))
            .withCursor(initialCursorValue)));

    final ConfiguredAirbyteCatalog configuredCatalog = new ConfiguredAirbyteCatalog()
        .withStreams(ImmutableList.of(airbyteStream));

    final List<AirbyteMessage> actualMessages = MoreIterators
        .toList(source.read(config, configuredCatalog, Jsons.jsonNode(state)));

    setEmittedAtToNull(actualMessages);

    final List<AirbyteMessage> expectedMessages = new ArrayList<>(expectedRecordMessages);
    expectedMessages.add(new AirbyteMessage()
        .withType(Type.STATE)
        .withState(new AirbyteStateMessage()
            .withData(Jsons.jsonNode(new JdbcState()
                .withCdc(false)
                .withStreams(Lists.newArrayList(new JdbcStreamState()
                    .withStreamName(airbyteStream.getStream().getName())
                    .withStreamNamespace(airbyteStream.getStream().getNamespace())
                    .withCursorField(ImmutableList.of(cursorField))
                    .withCursor(endCursorValue)))))));

    assertEquals(expectedMessages, actualMessages);
  }

  // get catalog and perform a defensive copy.
  private ConfiguredAirbyteCatalog getConfiguredCatalogWithOneStream(final String defaultNamespace) {
    final ConfiguredAirbyteCatalog catalog = CatalogHelpers.toDefaultConfiguredCatalog(getCatalog(defaultNamespace));
    // Filter to only keep the main stream name as configured stream
    catalog.withStreams(
        catalog.getStreams().stream().filter(s -> s.getStream().getName().equals(streamName))
            .collect(Collectors.toList()));
    return catalog;
  }

  private AirbyteCatalog getCatalog(final String defaultNamespace) {
    return new AirbyteCatalog().withStreams(Lists.newArrayList(
        CatalogHelpers.createAirbyteStream(
            TABLE_NAME,
            defaultNamespace,
            Field.of(COL_ID, JsonSchemaPrimitive.NUMBER),
            Field.of(COL_NAME, JsonSchemaPrimitive.STRING),
            Field.of(COL_UPDATED_AT, JsonSchemaPrimitive.STRING))
            .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL))
            .withSourceDefinedPrimaryKey(List.of(List.of(COL_ID))),
        CatalogHelpers.createAirbyteStream(
            TABLE_NAME_WITHOUT_PK,
            defaultNamespace,
            Field.of(COL_ID, JsonSchemaPrimitive.NUMBER),
            Field.of(COL_NAME, JsonSchemaPrimitive.STRING),
            Field.of(COL_UPDATED_AT, JsonSchemaPrimitive.STRING))
            .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL))
            .withSourceDefinedPrimaryKey(Collections.emptyList()),
        CatalogHelpers.createAirbyteStream(
            TABLE_NAME_COMPOSITE_PK,
            defaultNamespace,
            Field.of(COL_FIRST_NAME, JsonSchemaPrimitive.STRING),
            Field.of(COL_LAST_NAME, JsonSchemaPrimitive.STRING),
            Field.of(COL_UPDATED_AT, JsonSchemaPrimitive.STRING))
            .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL))
            .withSourceDefinedPrimaryKey(
                List.of(List.of(COL_FIRST_NAME), List.of(COL_LAST_NAME)))));
  }

  private List<AirbyteMessage> getTestMessages() {
    return Lists.newArrayList(
        new AirbyteMessage().withType(Type.RECORD)
            .withRecord(new AirbyteRecordMessage().withStream(streamName).withNamespace(getDefaultNamespace())
                .withData(Jsons.jsonNode(ImmutableMap
                    .of(COL_ID, ID_VALUE_1,
                        COL_NAME, "picard",
                        COL_UPDATED_AT, "2004-10-19T00:00:00Z")))),
        new AirbyteMessage().withType(Type.RECORD)
            .withRecord(new AirbyteRecordMessage().withStream(streamName).withNamespace(getDefaultNamespace())
                .withData(Jsons.jsonNode(ImmutableMap
                    .of(COL_ID, ID_VALUE_2,
                        COL_NAME, "crusher",
                        COL_UPDATED_AT,
                        "2005-10-19T00:00:00Z")))),
        new AirbyteMessage().withType(Type.RECORD)
            .withRecord(new AirbyteRecordMessage().withStream(streamName).withNamespace(getDefaultNamespace())
                .withData(Jsons.jsonNode(ImmutableMap
                    .of(COL_ID, ID_VALUE_3,
                        COL_NAME, "vash",
                        COL_UPDATED_AT, "2006-10-19T00:00:00Z")))));
  }

  private ConfiguredAirbyteStream createTableWithSpaces() throws SQLException {
    final String tableNameWithSpaces = TABLE_NAME_WITH_SPACES + "2";
    final String streamName2 = tableNameWithSpaces;

    database.execute(connection -> {
      connection.createStatement()
          .execute(String.format("CREATE TABLE %s(id INTEGER, %s VARCHAR(200))",
              getFullyQualifiedTableName(JdbcUtils.enquoteIdentifier(connection, tableNameWithSpaces)),
              JdbcUtils.enquoteIdentifier(connection, COL_LAST_NAME_WITH_SPACE)));
      connection.createStatement()
          .execute(String.format("INSERT INTO %s(id, %s) VALUES (1,'picard')",
              getFullyQualifiedTableName(JdbcUtils.enquoteIdentifier(connection, tableNameWithSpaces)),
              JdbcUtils.enquoteIdentifier(connection, COL_LAST_NAME_WITH_SPACE)));
      connection.createStatement()
          .execute(String.format("INSERT INTO %s(id, %s) VALUES (2, 'crusher')",
              getFullyQualifiedTableName(JdbcUtils.enquoteIdentifier(connection, tableNameWithSpaces)),
              JdbcUtils.enquoteIdentifier(connection, COL_LAST_NAME_WITH_SPACE)));
      connection.createStatement()
          .execute(String.format("INSERT INTO %s(id, %s) VALUES (3, 'vash')",
              getFullyQualifiedTableName(JdbcUtils.enquoteIdentifier(connection, tableNameWithSpaces)),
              JdbcUtils.enquoteIdentifier(connection, COL_LAST_NAME_WITH_SPACE)));
    });

    return CatalogHelpers.createConfiguredAirbyteStream(
        streamName2,
        getDefaultNamespace(),
        Field.of(COL_ID, JsonSchemaPrimitive.NUMBER),
        Field.of(COL_LAST_NAME_WITH_SPACE, JsonSchemaPrimitive.STRING));
  }

  public String getFullyQualifiedTableName(String tableName) {
    return JdbcUtils.getFullyQualifiedTableName(getDefaultSchemaName(), tableName);
  }

  public void createSchemas() throws SQLException {
    if (supportsSchemas()) {
      for (String schemaName : TEST_SCHEMAS) {
        final String dropSchemaQuery = String.format("CREATE SCHEMA %s;", schemaName);
        database.execute(connection -> connection.createStatement().execute(dropSchemaQuery));
      }
    }
  }

  public void dropSchemas() throws SQLException {
    if (supportsSchemas()) {
      for (String schemaName : TEST_SCHEMAS) {
        final String dropSchemaQuery = String
            .format("DROP SCHEMA IF EXISTS %s CASCADE", schemaName);
        database.execute(connection -> connection.createStatement().execute(dropSchemaQuery));
      }
    }
  }

  private JsonNode convertIdBasedOnDatabase(int idValue) {
    if (getDriverClass().toLowerCase().contains("oracle")) {
      return Jsons.jsonNode(BigDecimal.valueOf(idValue));
    } else {
      return Jsons.jsonNode(idValue);
    }
  }

  private String getDefaultSchemaName() {
    return supportsSchemas() ? SCHEMA_NAME : null;
  }

  private String getDefaultNamespace() {
    // mysql does not support schemas. it namespaces using database names instead.
    if (getDriverClass().toLowerCase().contains("mysql")) {
      return config.get("database").asText();
    } else {
      return SCHEMA_NAME;
    }
  }

  private static void setEmittedAtToNull(Iterable<AirbyteMessage> messages) {
    for (AirbyteMessage actualMessage : messages) {
      if (actualMessage.getRecord() != null) {
        actualMessage.getRecord().setEmittedAt(null);
      }
    }
  }

}
