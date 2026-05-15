package datart.server.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 从 {@code temperature_data_table.algorithm_analysis_conclusion} JSON 中拆出的常用标量，
 * 与历史 SQL 视图列名对齐，便于业务在 Java 中直接使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrainAlgorithmDerivedFields {

    private Integer avgTempHotspotExceptionCount;

    private Integer isolationForestExceptionCount;

    private Integer dailyGrowthHotspotExceptionCount;

    private Integer kurtosisHotLayerExceptionCount;

    private BigDecimal grainTemperatureScore;

    private String grainStorageStatus;
}
