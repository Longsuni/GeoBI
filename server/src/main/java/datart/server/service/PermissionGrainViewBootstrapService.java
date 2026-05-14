package datart.server.service;

import datart.server.base.dto.GrainViewBootstrapSummary;
import datart.server.base.params.GrainViewBootstrapParam;

import java.util.List;

/**
 * 从外部 PostgreSQL（与 {@code datart.permission.external-auth-source-id} 一致）按当前登录用户查询权限 org + 区域，
 * 在指定 Datart 组织下批量创建/更新「粮温」SQL 视图（目录：外部 org → 子级：按 region 的视图）。
 */
public interface PermissionGrainViewBootstrapService {

    /**
     * 仅查 permissions.org_ids 并解析为外部组织 id 列表（不写 Datart 视图）；供 jump-login 传入 {@link GrainViewBootstrapParam#setPermissionOrgIds}。
     *
     * @param datartUsername 与 permissions.user_id 对齐的 Datart 登录名（通常为外部 user_id 文本）
     */
    List<String> fetchPermissionOrgIds(String datartUsername) throws Exception;

    /**
     * @param param Datart orgId、业务 sourceId 必填
     * @return 每项创建/更新结果
     */
    GrainViewBootstrapSummary syncGrainTemperatureViews(GrainViewBootstrapParam param) throws Exception;
}
