## 改动说明

<!-- 简述本次改动的目的和内容 -->

## 改动类型

- [ ] 新功能
- [ ] Bug 修复
- [ ] 重构/优化
- [ ] 文档
- [ ] 测试

## 自检清单

- [ ] 代码无硬编码值（可配参数走 yml → Properties → RuntimeConfig）
- [ ] 业务逻辑返回明确拒绝原因而非抛异常
- [ ] Vue DOM 模板自定义元素用显式闭合标签
- [ ] 不适用的值用 `-` 显示
- [ ] TDD-SPEC.md 已同步更新（新增功能/修复 Bug 时）
- [ ] `mvn test` 通过（Tests run: N, Failures: 0, Errors: 0）
- [ ] 改动符合 DJI Cloud API 官方文档

## 验证结果

```
mvn test 结果：
Tests run: N, Failures: N, Errors: N, Skipped: N
```
