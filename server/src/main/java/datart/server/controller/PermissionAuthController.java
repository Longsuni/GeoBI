/*
 * Datart
 * <p>
 * Copyright 2021
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package datart.server.controller;

import datart.core.base.annotations.SkipLogin;
import datart.core.base.consts.Const;
import datart.core.base.exception.ParamException;
import datart.core.entity.Source;
import datart.security.exception.PermissionDeniedException;
import datart.server.base.dto.ExternalApiResponse;
import datart.server.base.dto.JumpLoginResult;
import datart.server.base.dto.OrganizationBaseInfo;
import datart.server.base.dto.ResponseData;
import datart.server.base.params.JumpLoginParam;
import datart.server.base.params.SourceCreateParam;
import datart.server.service.ExternalSysUserAuthService;
import datart.server.service.OrgService;
import datart.server.service.SourceService;
import datart.server.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部权限对齐：从配置的 PostgreSQL 数据源读取 {@code public.sys_user}，按 Datart 登录用户名匹配一行基础信息（不含密码）。
 */
@Slf4j
@Api
@RestController
@RequestMapping(value = "/permission-auth")
public class PermissionAuthController extends BaseController {

    @Value("${datart.jump-login.auto-create-source-enabled:false}")
    private boolean jumpLoginAutoCreateSourceEnabled;

    @Value("${datart.jump-login.auto-create-source-name:}")
    private String jumpLoginAutoCreateSourceName;

    @Value("${datart.jump-login.auto-create-source-type:JDBC}")
    private String jumpLoginAutoCreateSourceType;

    /** 与前端数据源 config 一致的 JSON 字符串；敏感信息请只写在配置/环境变量中，勿提交仓库 */
    @Value("${datart.jump-login.auto-create-source-config:}")
    private String jumpLoginAutoCreateSourceConfig;

    private final ExternalSysUserAuthService externalSysUserAuthService;

    private final UserService userService;

    private final SourceService sourceService;

    private final OrgService orgService;

    public PermissionAuthController(ExternalSysUserAuthService externalSysUserAuthService,
                                    UserService userService,
                                    SourceService sourceService,
                                    OrgService orgService) {
        this.externalSysUserAuthService = externalSysUserAuthService;
        this.userService = userService;
        this.sourceService = sourceService;
        this.orgService = orgService;
    }

    @SkipLogin
    @ApiOperation(value = "外部跳转：username 注册（默认密码 123456）并登录；响应含 orgId（Owner 组织优先，否则首个所属组织，无组织时为 null）。可选在 yml 开启 datart.jump-login.auto-create-source-* 后自动建源")
    @PostMapping(value = "/jump-login")
    public ExternalApiResponse<Map<String, Object>> jumpLogin(@Validated @RequestBody JumpLoginParam param,
                                                              HttpServletResponse response) throws Exception {
        JumpLoginResult result = userService.jumpRegisterAndLogin(param.getUsername());
        response.setHeader(Const.TOKEN, result.getToken());
        Map<String, Object> data = new LinkedHashMap<>(12);
        data.put("user", result.getUser());
        data.put("externalSysUser", result.getExternalSysUser());
        data.put("token", result.getToken());
        List<OrganizationBaseInfo> orgs = orgService.listOrganizations();
        String orgId = resolveOrgIdForJumpLoginAutoSource(orgs);
        data.put("orgId", orgId);
        if (!jumpLoginAutoCreateSourceEnabled) {
            data.put("sourceCreateSkipped", "未开启自动创建数据源：请在 datart.jump-login 下设置 auto-create-source-enabled: true，并配置 auto-create-source-name 与 auto-create-source-config");
            return ExternalApiResponse.success(data, "登录成功");
        }
        String name = StringUtils.trimToEmpty(jumpLoginAutoCreateSourceName);
        String type = StringUtils.defaultIfBlank(StringUtils.trimToNull(jumpLoginAutoCreateSourceType), "JDBC");
        String cfg = StringUtils.trimToEmpty(jumpLoginAutoCreateSourceConfig);
        if (StringUtils.isAnyBlank(name, cfg)) {
            data.put("sourceCreateSkipped", "已开启跳转自动建源但未配置 datart.jump-login.auto-create-source-name 或 auto-create-source-config");
            return ExternalApiResponse.success(data, "登录成功");
        }
        if (orgId == null) {
            data.put("sourceCreateSkipped", "用户未加入任何 Datart 组织，无法自动创建数据源");
        } else {
            SourceCreateParam sourceCreateParam = new SourceCreateParam();
            sourceCreateParam.setName(name);
            sourceCreateParam.setType(type);
            sourceCreateParam.setOrgId(orgId);
            sourceCreateParam.setConfig(cfg);
            try {
                String parentId = StringUtils.trimToNull(sourceCreateParam.getParentId());
                Source existing = sourceService.findExistingSourceForCreate(orgId, parentId, name);
                if (existing != null) {
                    log.debug("jump-login: same-name source exists under org, reuse without create, orgId={}, name={}, sourceId={}",
                            orgId, name, existing.getId());
                    data.put("source", existing);
                } else {
                    Source source = sourceService.createSource(sourceCreateParam);
                    data.put("source", source);
                }
            } catch (PermissionDeniedException e) {
                log.warn("jump-login: auto create source permission denied, orgId={}", orgId, e);
                data.put("sourceCreateSkipped", "当前用户在该组织下无创建数据源权限（非组织 Owner 时需在角色中授予数据源创建权限）");
            } catch (ParamException e) {
                log.warn("jump-login: auto create source failed, orgId={}", orgId, e);
                data.put("sourceCreateSkipped", e.getMessage());
            }
        }
        return ExternalApiResponse.success(data, "登录成功");
    }

    /**
     * 与前端在「当前组织」下新建数据源一致：优先使用当前用户为 {@link datart.security.manager.DatartSecurityManager#isOrgOwner(String)} 的组织；
     * 否则使用用户所属第一个组织（TEAM 普通成员等场景需管理员预先配置数据源创建类权限）。
     */
    private String resolveOrgIdForJumpLoginAutoSource(List<OrganizationBaseInfo> orgs) {
        if (orgs == null || orgs.isEmpty()) {
            return null;
        }
        for (OrganizationBaseInfo o : orgs) {
            if (securityManager.isOrgOwner(o.getId())) {
                return o.getId();
            }
        }
        return orgs.get(0).getId();
    }

    @ApiOperation(value = "当前登录用户在外部 sys_user 表中的基础信息（用于权限校验）")
    @GetMapping(value = "/external-sys-user")
    public ResponseData<Map<String, Object>> getExternalSysUser() throws Exception {
        return ResponseData.success(externalSysUserAuthService.loadExternalSysUserForCurrentLogin());
    }
}
