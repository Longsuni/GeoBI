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

package datart.server.service.impl;

import datart.core.base.exception.Exceptions;
import datart.core.base.exception.ParamException;
import datart.security.exception.AuthException;
import datart.core.data.provider.Column;
import datart.core.data.provider.Dataframe;
import com.google.common.collect.Sets;
import datart.core.base.consts.ValueType;
import datart.core.base.consts.VariableTypeEnum;
import datart.core.data.provider.ScriptType;
import datart.core.data.provider.ScriptVariable;
import datart.core.entity.User;
import datart.server.base.params.TestExecuteParam;
import datart.server.service.BaseService;
import datart.server.service.DataProviderService;
import datart.server.service.ExternalSysUserAuthService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExternalSysUserAuthServiceImpl extends BaseService implements ExternalSysUserAuthService {

    /**
     * 使用 QUERY 变量 {@code $EXT_USERNAME$}、{@code $EXT_USER_ID$}（跳转登录后 Datart 用户名可与外部 {@code username} 不同，故用 OR 匹配）。
     */
    private static final String SYS_USER_SELECT_LOOSE = ""
            + "SELECT user_id, username, nick_name, user_type, email, phone_number, sex, avatar, "
            + "status, deleted, create_by, create_time, update_by, update_time, remark, super_admin "
            + "FROM public.sys_user "
            + "WHERE (deleted IS NULL OR deleted = '0') "
            + "AND (username = $EXT_USERNAME$ OR user_id::text = $EXT_USER_ID$)";

    private static final String SYS_USER_SELECT_BY_USER_ID = ""
            + "SELECT user_id, username, nick_name, user_type, email, phone_number, sex, avatar, "
            + "status, deleted, create_by, create_time, update_by, update_time, remark, super_admin "
            + "FROM public.sys_user "
            + "WHERE (deleted IS NULL OR deleted = '0') AND user_id::text = $EXT_USER_ID$";

    @Value("${datart.permission.external-auth-source-id:}")
    private String externalAuthSourceId;

    private final DataProviderService dataProviderService;

    public ExternalSysUserAuthServiceImpl(DataProviderService dataProviderService) {
        this.dataProviderService = dataProviderService;
    }

    @Override
    public Map<String, Object> loadExternalSysUserForCurrentLogin() throws Exception {
        if (StringUtils.isBlank(externalAuthSourceId)) {
            Exceptions.tr(ParamException.class, "error.param.empty", "datart.permission.external-auth-source-id");
        }
        User user = getCurrentUser();
        if (user == null) {
            Exceptions.tr(AuthException.class, "login.not-login");
        }
        if (StringUtils.isBlank(user.getUsername())) {
            Exceptions.tr(ParamException.class, "error.param.empty", "username");
        }
        return querySysUserLoose(user.getUsername());
    }

    @Override
    public Map<String, Object> loadExternalSysUserByUsername(String username) throws Exception {
        if (StringUtils.isBlank(username)) {
            Exceptions.tr(ParamException.class, "error.param.empty", "username");
        }
        return querySysUserLoose(username.trim());
    }

    @Override
    public Map<String, Object> loadExternalSysUserByUserId(String userId) throws Exception {
        if (StringUtils.isBlank(userId)) {
            Exceptions.tr(ParamException.class, "error.param.empty", "user-id");
        }
        if (StringUtils.isBlank(externalAuthSourceId)) {
            Exceptions.tr(ParamException.class, "error.param.empty", "datart.permission.external-auth-source-id");
        }
        TestExecuteParam param = new TestExecuteParam();
        param.setSourceId(externalAuthSourceId);
        param.setScript(SYS_USER_SELECT_BY_USER_ID);
        param.setScriptType(ScriptType.SQL);
        param.setSize(5);
        ScriptVariable extUserId = new ScriptVariable(
                "EXT_USER_ID",
                VariableTypeEnum.QUERY,
                ValueType.STRING,
                Sets.newHashSet(userId.trim()),
                false);
        param.setVariables(Collections.singletonList(extUserId));
        Dataframe df = dataProviderService.testExecuteWithoutSourcePermission(param);
        return firstRowAsMap(df);
    }

    private Map<String, Object> querySysUserLoose(String identityKey) throws Exception {
        if (StringUtils.isBlank(externalAuthSourceId)) {
            Exceptions.tr(ParamException.class, "error.param.empty", "datart.permission.external-auth-source-id");
        }
        TestExecuteParam param = new TestExecuteParam();
        param.setSourceId(externalAuthSourceId);
        param.setScript(SYS_USER_SELECT_LOOSE);
        param.setScriptType(ScriptType.SQL);
        param.setSize(5);
        List<ScriptVariable> vars = new ArrayList<>(2);
        vars.add(new ScriptVariable(
                "EXT_USERNAME",
                VariableTypeEnum.QUERY,
                ValueType.STRING,
                Sets.newHashSet(identityKey),
                false));
        vars.add(new ScriptVariable(
                "EXT_USER_ID",
                VariableTypeEnum.QUERY,
                ValueType.STRING,
                Sets.newHashSet(identityKey),
                false));
        param.setVariables(vars);
        Dataframe df = dataProviderService.testExecuteWithoutSourcePermission(param);
        return firstRowAsMap(df);
    }

    private static Map<String, Object> firstRowAsMap(Dataframe df) {
        if (df == null || CollectionUtils.isEmpty(df.getColumns()) || CollectionUtils.isEmpty(df.getRows())) {
            return null;
        }
        List<Object> row = df.getRows().get(0);
        Map<String, Object> map = new LinkedHashMap<>();
        List<Column> cols = df.getColumns();
        for (int i = 0; i < cols.size() && i < row.size(); i++) {
            map.put(cols.get(i).columnName(), row.get(i));
        }
        return map;
    }
}
