# Parent 原像

Parent：6fcdf747e17cb5094121f609ae33f7734fda2ae7
Tree：02e30bd0778ad2eff63b091429b89e7f17895f8f

四个 owned 文件原 Git blob：

- ci/capacity_mixed_probe.py：d6d6c1d1d67e121b9047984c79a2f9352dc48100
- ci/gameplay_process_recovery_probe.py：d9750502204cfccf54485693cad569eeb1fe4d03
- ci/test_capacity_mixed_probe.py：cc5389110da1144a12fa9cc579916f2857e164b5
- ci/test_gameplay_process_recovery_probe.py：0675dad6ad7d8a749d56d46f457424723f0f6c11

只读依赖（未修改）：

- GameplayProcessRecoveryChild.java：b710b101a256e83609063054819f2dd150f21d27
- ci/upgrade-startup/application.properties：d9f7afdbfcdc5122ef523b3c6d3507fe9b3236e8
- ci/authenticated_redis_recovery_probe.py：1037a5e08d2d59475b939158040e821cabca9eba
- ci/authenticated-redis-recovery.sh：90a676e89236cd1babfc4f6937ae9c0918192007

完整四文件增量见 owned.review.patch；后像见 POSTIMAGE.sha256。源码读取、diff/hash 与公共 Git 对象交付不等于测试或 CI。
