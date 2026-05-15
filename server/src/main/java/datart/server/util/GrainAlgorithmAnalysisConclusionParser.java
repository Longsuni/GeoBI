package datart.server.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import datart.server.base.dto.GrainAlgorithmDerivedFields;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;

/**
 * 解析 {@code temperature_data_table.algorithm_analysis_conclusion} 整包 JSON：
 * 拆常用标量、仅 algorithms 摘要、以及各算法 {@code exceptionCount}（供图表）。
 */
public final class GrainAlgorithmAnalysisConclusionParser {

    private static final String KEY_ALGORITHMS = "algorithms";

    private static final String KEY_AVG_TEMP_HOTSPOT = "AvgTempHotspotDetector";

    private static final String KEY_ISOLATION_FOREST = "IsolationForestAnomalyDetection";

    private static final String KEY_DAILY_GROWTH = "DailyGrowthHotspotDetector";

    private static final String KEY_KURTOSIS = "KurtosisAnomalyDetection";

    private static final String KEY_GRAIN_TEMP_DETECTION = "grain_temperature_detection";

    private GrainAlgorithmAnalysisConclusionParser() {
    }

    /**
     * 与历史视图列对齐的标量字段。
     */
    public static GrainAlgorithmDerivedFields parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return GrainAlgorithmDerivedFields.builder().build();
        }
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONObject algorithms = root.getJSONObject(KEY_ALGORITHMS);
            if (algorithms == null) {
                return GrainAlgorithmDerivedFields.builder().build();
            }
            JSONObject grainDet = algorithms.getJSONObject(KEY_GRAIN_TEMP_DETECTION);
            BigDecimal score = null;
            String storageStatus = null;
            if (grainDet != null) {
                Double sc = grainDet.getDouble("score");
                if (sc != null) {
                    score = BigDecimal.valueOf(sc);
                }
                storageStatus = grainDet.getString("grain_storage_status");
            }
            return GrainAlgorithmDerivedFields.builder()
                    .avgTempHotspotExceptionCount(readExceptionCount(algorithms.getJSONObject(KEY_AVG_TEMP_HOTSPOT)))
                    .isolationForestExceptionCount(readExceptionCount(algorithms.getJSONObject(KEY_ISOLATION_FOREST)))
                    .dailyGrowthHotspotExceptionCount(readExceptionCount(algorithms.getJSONObject(KEY_DAILY_GROWTH)))
                    .kurtosisHotLayerExceptionCount(readExceptionCount(algorithms.getJSONObject(KEY_KURTOSIS)))
                    .grainTemperatureScore(score)
                    .grainStorageStatus(storageStatus)
                    .build();
        } catch (Exception ignored) {
            return GrainAlgorithmDerivedFields.builder().build();
        }
    }

    /**
     * 将整包 JSON 压成仅 {@code algorithms} 对象字符串，减小下行体积。
     */
    public static String parseAlgorithmsSummaryJson(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "{}";
        }
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONObject algorithms = root.getJSONObject(KEY_ALGORITHMS);
            return algorithms != null ? algorithms.toJSONString() : "{}";
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /**
     * 各算法及其 {@code conclusion.exceptionCount}，JSON 数组，便于前端拆行画图。
     * 元素示例：{@code {"algorithm":"AvgTempHotspotDetector","exceptionCount":54}}；
     * 无 {@code conclusion} 或无数时 {@code exceptionCount} 为 {@code null}。
     */
    public static String parseAlgorithmExceptionMetricsJson(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "[]";
        }
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONObject algorithms = root.getJSONObject(KEY_ALGORITHMS);
            if (algorithms == null || algorithms.isEmpty()) {
                return "[]";
            }
            JSONArray out = new JSONArray();
            for (String name : algorithms.keySet()) {
                Object v = algorithms.get(name);
                if (!(v instanceof JSONObject)) {
                    continue;
                }
                JSONObject one = new JSONObject();
                one.put("algorithm", name);
                one.put("exceptionCount", readExceptionCount((JSONObject) v));
                out.add(one);
            }
            return out.toJSONString();
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static Integer readExceptionCount(JSONObject algoEntry) {
        if (algoEntry == null) {
            return null;
        }
        JSONObject conclusion = algoEntry.getJSONObject("conclusion");
        if (conclusion == null) {
            return null;
        }
        return conclusion.getInteger("exceptionCount");
    }
}
