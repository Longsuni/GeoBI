package datart.server.util;

import datart.core.base.consts.ValueType;
import datart.core.data.provider.Column;
import datart.core.data.provider.Dataframe;
import datart.core.data.provider.SelectColumn;
import datart.server.base.dto.GrainAlgorithmDerivedFields;
import datart.server.base.params.ViewExecuteParam;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 粮温引导 SQL 在 JDBC 返回后追加算法拆列：脚本须含 {@link #SCRIPT_MARKER}（由 {@code buildGrainTemperatureScript} 写入）。
 * 使用块注释而非 {@code --} 行注释，避免 Calcite 校验 {@link datart.data.provider.calcite.SqlValidateUtils#validateQuery(String, boolean)}
 * 将首词误判为 {@code --} 而报「不允许的SQL操作」。
 * 将库内 {@code algorithm_analysis_conclusion} 在内存中解析为标量列及 {@link GrainAlgorithmAnalysisConclusionParser#parseAlgorithmExceptionMetricsJson(String)}，
 * 不在返回结果中保留原文或摘要 JSON（过长）。
 */
public final class GrainTemperatureDataframeExpander {

    /** 新脚本使用块注释，紧挨 {@code WITH}，可被 Calcite 解析 */
    public static final String SCRIPT_MARKER = "/* @datart:grain-temperature-expand-algo */";

    /** 旧版曾写入行注释；仍识别以触发后处理，且由 {@link datart.data.provider.calcite.SqlValidateUtils} 首词逻辑兼容 */
    public static final String LEGACY_SCRIPT_MARKER_LINE = "-- @datart:grain-temperature-expand-algo";

    public static boolean isExpandGrainTemperatureScript(String script) {
        return StringUtils.isNotBlank(script)
                && (script.contains(SCRIPT_MARKER) || script.contains(LEGACY_SCRIPT_MARKER_LINE));
    }

    /** 替换整包原文后的列名（与 SQL 别名、视图 model 一致） */
    public static final String ALGORITHMS_JSON_COLUMN = "algorithms_json";

    /**
     * 仅由 {@link #expandIfMarked(String, Dataframe)} 在内存中追加的列，不存在于 PG 子查询结果中；
     * 若进入 Calcite 外层包装（DATART_VTABLE），会导致「字段不存在」。
     */
    private static final Set<String> JAVA_ONLY_OUTPUT_SIMPLE_NAMES;

    static {
        Set<String> s = new HashSet<>();
        s.add("avg_temp_hotspot_exception_count");
        s.add("isolation_forest_exception_count");
        s.add("daily_growth_hotspot_exception_count");
        s.add("kurtosis_hot_layer_exception_count");
        s.add("grain_temperature_score");
        s.add("grain_storage_status");
        s.add("algorithm_exception_metrics");
        s.add(ALGORITHMS_JSON_COLUMN.toLowerCase(Locale.ROOT));
        JAVA_ONLY_OUTPUT_SIMPLE_NAMES = Collections.unmodifiableSet(s);
    }

    private GrainTemperatureDataframeExpander() {
    }

    /** 最后一级列名是否为「仅 Java 展开产出」列（忽略路径前缀，如 DATART_VTABLE.xxx） */
    public static boolean isJavaOnlyDerivedOutputColumnKey(String columnKey) {
        if (StringUtils.isBlank(columnKey)) {
            return false;
        }
        int dot = columnKey.lastIndexOf('.');
        String last = dot >= 0 ? columnKey.substring(dot + 1) : columnKey;
        return JAVA_ONLY_OUTPUT_SIMPLE_NAMES.contains(last.toLowerCase(Locale.ROOT));
    }

    private static boolean isJavaOnlyDerivedColumnOperator(datart.core.data.provider.sql.ColumnOperator op) {
        return op != null && isJavaOnlyDerivedOutputColumnKey(op.getColumnKey());
    }

    /**
     * 从视图执行参数中移除对「仅 Java 产出列」的引用，避免 SqlBuilder 生成非法外层 SQL。
     * 展开后这些列仍会出现在 {@link Dataframe} 中。
     */
    public static void stripJavaOnlyDerivedFromViewExecute(ViewExecuteParam param, String viewScript) {
        if (param == null || !isExpandGrainTemperatureScript(viewScript)) {
            return;
        }
        removeIfColumnOperators(param.getColumns(), GrainTemperatureDataframeExpander::isJavaOnlyDerivedColumnOperator);
        removeIfColumnOperators(param.getGroups(), GrainTemperatureDataframeExpander::isJavaOnlyDerivedColumnOperator);
        removeIfColumnOperators(param.getAggregators(), GrainTemperatureDataframeExpander::isJavaOnlyDerivedColumnOperator);
        removeIfColumnOperators(param.getFilters(), GrainTemperatureDataframeExpander::isJavaOnlyDerivedColumnOperator);
        removeIfColumnOperators(param.getOrders(), GrainTemperatureDataframeExpander::isJavaOnlyDerivedColumnOperator);
        if (!CollectionUtils.isEmpty(param.getFunctionColumns())) {
            param.getFunctionColumns().removeIf(fc -> fc != null && fc.getAlias() != null
                    && JAVA_ONLY_OUTPUT_SIMPLE_NAMES.contains(fc.getAlias().toLowerCase(Locale.ROOT)));
        }
    }

    /** 从视图 model 解析出的 schema 中移除仅 Java 产出列，避免与外层查询不一致 */
    public static void stripJavaOnlyDerivedFromSchema(Map<String, Column> schema, String viewScript) {
        if (schema == null || schema.isEmpty() || !isExpandGrainTemperatureScript(viewScript)) {
            return;
        }
        schema.entrySet().removeIf(e -> e.getValue() != null
                && JAVA_ONLY_OUTPUT_SIMPLE_NAMES.contains(e.getValue().columnName().toLowerCase(Locale.ROOT)));
    }

    /**
     * 从列权限集合中移除仅 Java 产出列；若为 {@code *} 则不变。
     */
    public static void stripJavaOnlyDerivedFromIncludeColumns(Set<SelectColumn> includeColumns, String viewScript) {
        if (includeColumns == null || includeColumns.isEmpty() || !isExpandGrainTemperatureScript(viewScript)) {
            return;
        }
        if (includeColumns.size() == 1) {
            SelectColumn only = includeColumns.iterator().next();
            if (only != null && "*".equals(only.getColumnKey())) {
                return;
            }
        }
        includeColumns.removeIf(sc -> sc != null && isJavaOnlyDerivedOutputColumnKey(sc.getColumnKey()));
    }

    private static <T extends datart.core.data.provider.sql.ColumnOperator> void removeIfColumnOperators(
            List<T> list, java.util.function.Predicate<T> removeIf) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        list.removeIf(removeIf);
    }

    public static void expandIfMarked(String script, Dataframe df) {
        if (df == null || StringUtils.isBlank(script) || !isExpandGrainTemperatureScript(script)) {
            return;
        }
        if (CollectionUtils.isEmpty(df.getColumns()) || df.getRows() == null) {
            return;
        }
        List<Column> columns = df.getColumns();
        if (columnIndex(columns, "avg_temp_hotspot_exception_count") >= 0
                || columnIndex(columns, "algorithm_exception_metrics") >= 0) {
            return;
        }
        int algoIdx = columnIndex(columns, ALGORITHMS_JSON_COLUMN);
        if (algoIdx < 0) {
            algoIdx = columnIndex(columns, "algorithm_analysis_conclusion");
        }
        if (algoIdx < 0) {
            return;
        }
        List<Column> newCols = new ArrayList<>(columns.size() + 7);
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            if (i == algoIdx) {
                newCols.add(Column.of(ValueType.STRING, ALGORITHMS_JSON_COLUMN));
            } else {
                newCols.add(c);
            }
        }
        newCols.add(Column.of(ValueType.NUMERIC, "avg_temp_hotspot_exception_count"));
        newCols.add(Column.of(ValueType.NUMERIC, "isolation_forest_exception_count"));
        newCols.add(Column.of(ValueType.NUMERIC, "daily_growth_hotspot_exception_count"));
        newCols.add(Column.of(ValueType.NUMERIC, "kurtosis_hot_layer_exception_count"));
        newCols.add(Column.of(ValueType.NUMERIC, "grain_temperature_score"));
        newCols.add(Column.of(ValueType.STRING, "grain_storage_status"));
        newCols.add(Column.of(ValueType.STRING, "algorithm_exception_metrics"));

        List<List<Object>> newRows = new ArrayList<>(df.getRows().size());
        for (List<Object> row : df.getRows()) {
            List<Object> nr = new ArrayList<>(row.size() + 7);
            for (int i = 0; i < row.size(); i++) {
                if (i == algoIdx) {
                    String raw = row.get(i) != null ? row.get(i).toString() : null;
                    nr.add(GrainAlgorithmAnalysisConclusionParser.parseAlgorithmsSummaryJson(raw));
                } else {
                    nr.add(i < row.size() ? row.get(i) : null);
                }
            }
            String raw = algoIdx < row.size() && row.get(algoIdx) != null ? row.get(algoIdx).toString() : null;
            GrainAlgorithmDerivedFields f = GrainAlgorithmAnalysisConclusionParser.parse(raw);
            nr.add(f.getAvgTempHotspotExceptionCount());
            nr.add(f.getIsolationForestExceptionCount());
            nr.add(f.getDailyGrowthHotspotExceptionCount());
            nr.add(f.getKurtosisHotLayerExceptionCount());
            nr.add(f.getGrainTemperatureScore());
            nr.add(f.getGrainStorageStatus());
            nr.add(GrainAlgorithmAnalysisConclusionParser.parseAlgorithmExceptionMetricsJson(raw));
            newRows.add(nr);
        }
        df.setColumns(newCols);
        df.setRows(newRows);
    }

    private static int columnIndex(List<Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (name.equalsIgnoreCase(columns.get(i).columnName())) {
                return i;
            }
        }
        return -1;
    }
}
