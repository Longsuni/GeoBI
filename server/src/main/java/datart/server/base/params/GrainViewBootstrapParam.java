package datart.server.base.params;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 按外部 PG 权限为当前用户批量同步「粮温」SQL 视图：先查 (org_id, region_code)，再在 Datart 组织下建目录 + 视图。
 */
@Data
public class GrainViewBootstrapParam {

    /** Datart 组织 ID（与前端保存视图时的 orgId 一致） */
    @NotBlank
    private String orgId;

    /** 粮情业务库在 Datart 中的数据源 ID（与前端 sourceId 一致） */
    @NotBlank
    private String sourceId;

    /**
     * 可选：jump-login 等场景已解析出的外部组织 id 列表；非空时不再查 permissions，仅按列表查 org_unit.region。
     */
    private List<String> permissionOrgIds;

    /**
     * 可选：根目录下已有父目录 ID；不传则在 org 根下按外部 org_id 建一级目录。
     */
    private String rootParentId;
}
