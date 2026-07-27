# 数据库历史修复方案（2026-07-27）

## 背景

`V20260725112037__document_qa.sql` 已创建以下模型部署：

- `codex2api-gpt-5-4-mini-vision`
- `codex2api-gpt-5-6-sol-text`
- `codex2api-gpt-5-6-sol-vision`

后续 `V20260725113615__document_summary.sql` 再次插入同一组唯一
`model_deployment.code`。已合入 `main` 的版本迁移保持不变，本修复通过
`B20260725165243__main_baseline.sql` 为新数据库提供一致的基线状态。

不得删除或手工伪造生产、共享数据库的 `flyway_schema_history`。
所有操作先备份数据库，并在目标环境的维护窗口执行。

## 环境分类

先执行只读检查：

```sql
select installed_rank, version, description, checksum, success
from flyway_schema_history
where version in ('20260725112037', '20260725113615')
order by installed_rank;

select code, count(*)
from model_deployment
where code in (
    'codex2api-gpt-5-4-mini-vision',
    'codex2api-gpt-5-6-sol-text',
    'codex2api-gpt-5-6-sol-vision'
)
group by code
order by code;
```

### 新建空数据库

直接使用包含 baseline 的正式构建执行 Flyway。空库应只执行
`B20260725165243__main_baseline.sql`，再执行版本高于该基线的迁移。

### 已成功执行且 `flyway validate` 通过

无需修复。继续执行后续迁移。

### 已成功执行，但 checksum 与 `main` 不一致

1. 确认文档问答、文档总结、模型部署、策略和选项数据完整。
2. 使用正式 `main` 的迁移目录执行 `flyway repair`。
3. 执行 `flyway validate`。
4. 验证三个共享 deployment 各只有一条。

`repair` 只用于对齐已经确认正确的迁移历史，不能替代数据核验。

### 停在文档总结迁移之前

1. 从正式 `main` 创建临时 worktree。
2. 在临时 worktree 执行：

   ```text
   git apply --unidiff-zero docs/database-repairs/20260727-document-summary-conflict-safe.patch
   ```

3. 使用临时 worktree 的迁移目录执行 Flyway，目标版本限定为
   `20260725113615`。
4. 核验文档总结的 FeatureDefinition、三个 FeatureVersion、模型策略和选项。
5. 切回未修改迁移的正式 `main`，执行 `flyway repair` 和
   `flyway validate`。
6. 再执行剩余迁移。

临时 worktree 不得提交或推送；补丁仅用于修复已经停滞的数据库。

## 验收

```sql
select code, current_version
from feature_definition
where code in ('document.qa', 'document.summary')
order by code;

select feature_code, capability, default_deployment_code
from feature_model_policy
where feature_code in ('document.qa', 'document.summary')
order by feature_code, capability;
```

验收要求：

- `flyway validate` 成功。
- 三个共享 deployment 各一条。
- `document.qa.current_version = 2`。
- `document.summary.current_version = 3`。
- PostgreSQL 16 空库迁移检查通过。
