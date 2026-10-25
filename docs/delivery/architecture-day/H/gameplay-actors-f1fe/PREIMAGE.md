# f1fe 原像与实际依赖

Parent f1fe75d646f8ae57864ee5603c68e70e7fabc854
Tree e293cc5976c22de88731ee792e75d16e6544fc7b

Owned Git blobs：

- ci/capacity_mixed_probe.py：dd477b5b12b948b81e44ff3d80467fdea9c73044
- ci/gameplay_process_recovery_probe.py：cd6750281abd9da2023927d34843a96ae11184cb
- ci/test_capacity_mixed_probe.py：bc616510593c61fac5a27911d99f7c937d29aff3
- ci/test_gameplay_process_recovery_probe.py：5d7ce37e8317216cafec13d8e64aad901e9dc80a

只读源依赖，无编辑：

- GameplayProcessRecoveryChild.java：b710b101a256e83609063054819f2dd150f21d27
- UmsMember.java：5d78e78306a804ed3a7871eb1871b1e442512a7e
- UmsMemberMapper.xml：75345e5225a673822ecf6af0a80c91e2d6b1f63c
- authenticated_redis_recovery_probe.py：dbc6180911b337c65ceccfd7aa302a8e7c61bd43
- scripts/e2e/build_schema.py：d1d66c1949fd426268b4d8a0ed29920d2081ed62
- 原 canonical base schema/seed 与实际 AppLoginService、MerchantCommerceService。
- 192a122f3f0716d132eb7075fc7dba303feb7f13 仅 root 证据 docs，不是代码 parent/运行源。

完整增量 owned.review.patch；所有 owned 后像 POSTIMAGE.sha256；所有执行验证 NOT_RUN。
