package io.confluent.ksql.execution.streams;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.plan.ExecutionStep;
import io.confluent.ksql.execution.plan.ExecutionStepPropertiesV1;
import io.confluent.ksql.execution.plan.KGroupedTableHolder;
import io.confluent.ksql.execution.plan.KTableHolder;
import io.confluent.ksql.execution.plan.KeySerdeFactory;
import io.confluent.ksql.execution.plan.PlanBuilder;
import io.confluent.ksql.execution.plan.TableGroupBy;
import io.confluent.ksql.execution.streams.TableGroupByBuilder.TableKeyValueMapper;
import io.confluent.ksql.execution.util.StructKeyUtil;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.logging.processing.ProcessingLogger;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.FormatFactory;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.SchemaUtil;
import java.util.List;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KGroupedTable;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Predicate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class TableGroupByBuilderTest {

  private static final LogicalSchema SCHEMA = LogicalSchema.builder()
      .withRowTime()
      .keyColumn(ColumnName.of("k0"), SqlTypes.DOUBLE)
      .valueColumn(ColumnName.of("PAC"), SqlTypes.BIGINT)
      .valueColumn(ColumnName.of("MAN"), SqlTypes.STRING)
      .build()
      .withMetaAndKeyColsInValue(false);

  private static final LogicalSchema REKEYED_SCHEMA = LogicalSchema.builder()
      .withRowTime()
      .keyColumn(SchemaUtil.ROWKEY_NAME, SqlTypes.STRING)
      .valueColumns(SCHEMA.value())
      .build();

  private static final PhysicalSchema REKEYED_PHYSICAL_SCHEMA =
      PhysicalSchema.from(REKEYED_SCHEMA, SerdeOption.none());

  private static final List<Expression> GROUPBY_EXPRESSIONS = ImmutableList.of(
      columnReference("PAC"),
      columnReference("MAN")
  );
  private static final QueryContext SOURCE_CONTEXT =
      new QueryContext.Stacker().push("foo").push("source").getQueryContext();
  private static final QueryContext STEP_CONTEXT =
      new QueryContext.Stacker().push("foo").push("groupby").getQueryContext();
  private static final ExecutionStepPropertiesV1 SOURCE_PROPERTIES =
      new ExecutionStepPropertiesV1(SOURCE_CONTEXT);
  private static final ExecutionStepPropertiesV1 PROPERTIES = new ExecutionStepPropertiesV1(
      STEP_CONTEXT
  );
  private static final io.confluent.ksql.execution.plan.Formats FORMATS = io.confluent.ksql.execution.plan.Formats
      .of(
      FormatInfo.of(FormatFactory.KAFKA.name()),
      FormatInfo.of(FormatFactory.JSON.name()),
      SerdeOption.none()
  );

  private static final Struct KEY = StructKeyUtil
      .keyBuilder(SchemaUtil.ROWKEY_NAME, SqlTypes.STRING).build("key");

  @Mock
  private KsqlQueryBuilder queryBuilder;
  @Mock
  private KsqlConfig ksqlConfig;
  @Mock
  private FunctionRegistry functionRegistry;
  @Mock
  private GroupedFactory groupedFactory;
  @Mock
  private ExecutionStep<KTableHolder<Struct>> sourceStep;
  @Mock
  private Serde<Struct> keySerde;
  @Mock
  private Serde<GenericRow> valueSerde;
  @Mock
  private Grouped<Struct, GenericRow> grouped;
  @Mock
  private KTable<Struct, GenericRow> sourceTable;
  @Mock
  private KTable<Struct, GenericRow> filteredTable;
  @Mock
  private KGroupedTable<Struct, GenericRow> groupedTable;
  @Captor
  private ArgumentCaptor<TableKeyValueMapper<Struct>> mapperCaptor;
  @Captor
  private ArgumentCaptor<Predicate<Struct, GenericRow>> predicateCaptor;
  @Mock
  private ProcessingLogger processingLogger;

  private PlanBuilder planBuilder;
  private TableGroupBy<Struct> groupBy;

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Before
  @SuppressWarnings("unchecked")
  public void init() {
    when(queryBuilder.getQueryId()).thenReturn(new QueryId("qid"));
    when(queryBuilder.getKsqlConfig()).thenReturn(ksqlConfig);
    when(queryBuilder.getFunctionRegistry()).thenReturn(functionRegistry);
    when(queryBuilder.buildKeySerde(any(), any(), any())).thenReturn(keySerde);
    when(queryBuilder.buildValueSerde(any(), any(), any())).thenReturn(valueSerde);
    when(queryBuilder.getProcessingLogger(any())).thenReturn(processingLogger);
    when(groupedFactory.create(any(), any(Serde.class), any())).thenReturn(grouped);
    when(sourceTable.filter(any())).thenReturn(filteredTable);
    when(filteredTable.groupBy(any(KeyValueMapper.class), any(Grouped.class)))
        .thenReturn(groupedTable);
    when(sourceStep.getProperties()).thenReturn(SOURCE_PROPERTIES);
    when(sourceStep.build(any())).thenReturn(
        KTableHolder.unmaterialized(sourceTable, SCHEMA, mock(KeySerdeFactory.class)));

    groupBy = new TableGroupBy<>(
        PROPERTIES,
        sourceStep,
        FORMATS,
        GROUPBY_EXPRESSIONS
    );
    planBuilder = new KSPlanBuilder(
        queryBuilder,
        mock(SqlPredicateFactory.class),
        mock(AggregateParamsFactory.class),
        new StreamsFactories(
            groupedFactory,
            mock(JoinedFactory.class),
            mock(MaterializedFactory.class),
            mock(StreamJoinedFactory.class),
            mock(ConsumedFactory.class)
        )
    );
  }

  @Test
  public void shouldPerformGroupByCorrectly() {
    // When:
    final KGroupedTableHolder result = groupBy.build(planBuilder);

    // Then:
    assertThat(result.getGroupedTable(), is(groupedTable));
    verify(sourceTable).filter(any());
    verify(filteredTable).groupBy(mapperCaptor.capture(), same(grouped));
    verifyNoMoreInteractions(filteredTable, sourceTable);
  }

  @Test
  public void shouldReturnCorrectSchema() {
    // When:
    final KGroupedTableHolder result = groupBy.build(planBuilder);

    // Then:
    assertThat(result.getSchema(), is(is(LogicalSchema.builder()
        .withRowTime()
        .keyColumn(SchemaUtil.ROWKEY_NAME, SqlTypes.STRING)
        .valueColumns(SCHEMA.value())
        .build())));
  }

  @Test
  public void shouldFilterNullRowsBeforeGroupBy() {
    // When:
    groupBy.build(planBuilder);

    // Then:
    verify(sourceTable).filter(predicateCaptor.capture());
    final Predicate<Struct, GenericRow> predicate = predicateCaptor.getValue();
    assertThat(predicate.test(KEY, new GenericRow()), is(true));
    assertThat(predicate.test(KEY, null),  is(false));
  }

  @Test
  public void shouldBuildGroupedCorrectlyForGroupBy() {
    // When:
    groupBy.build(planBuilder);

    // Then:
    verify(groupedFactory).create("foo-groupby", keySerde, valueSerde);
  }

  @Test
  public void shouldBuildKeySerdeCorrectlyForGroupBy() {
    // When:
    groupBy.build(planBuilder);

    // Then:
    verify(queryBuilder).buildKeySerde(
        FORMATS.getKeyFormat(),
        REKEYED_PHYSICAL_SCHEMA,
        STEP_CONTEXT
    );
  }

  @Test
  public void shouldBuildValueSerdeCorrectlyForGroupBy() {
    // When:
    groupBy.build(planBuilder);

    // Then:
    verify(queryBuilder).buildValueSerde(
        FORMATS.getValueFormat(),
        REKEYED_PHYSICAL_SCHEMA,
        STEP_CONTEXT
    );
  }

  private static Expression columnReference(final String column) {
    return new UnqualifiedColumnReferenceExp(ColumnName.of(column));
  }
}