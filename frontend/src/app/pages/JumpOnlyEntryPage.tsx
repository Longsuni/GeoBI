/**
 * 未登录且关闭本地登录时的占位页：由统一门户跳转并写入 token（如 /authorization?authorization_token=…）。
 */
import { Typography } from 'antd';
import { LayoutWithBrand } from 'app/components';
import { Version } from 'app/components/Version';
import { selectSystemInfo } from 'app/slice/selectors';
import React from 'react';
import { useSelector } from 'react-redux';

const { Paragraph } = Typography;

export function JumpOnlyEntryPage() {
  const systemInfo = useSelector(selectSystemInfo);

  return (
    <LayoutWithBrand>
      <Paragraph style={{ maxWidth: 480, marginBottom: 24 }}>
        本环境未开放本地账号登录。请从统一门户完成跳转，或使用管理员提供的带授权信息的访问地址进入系统。
      </Paragraph>
      <Version version={systemInfo?.version} />
    </LayoutWithBrand>
  );
}
