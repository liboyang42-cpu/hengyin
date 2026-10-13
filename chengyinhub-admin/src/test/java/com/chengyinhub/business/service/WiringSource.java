package com.chengyinhub.business.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 接线测试的共用工具:按仓库相对路径读源码。
 *
 * <p>为什么接线测试要读源码:有些「写入点被删」的改动**编译通过且全部测试仍绿**(评审用 mutation
 * 实测过),mock 与编译器都管不着,只能断言源码里那一行还在。
 *
 * <p>⚠️ 它只守「代码长这样」,守不住「运行时真写进去了」—— 后者只有部署后查库能回答。
 *
 * <p>两个候选路径是因为 surefire 的工作目录随聚合构建(仓库根)与单模块构建(chengyinhub-admin/)而变。
 */
final class WiringSource {
    private WiringSource() {}

    static String read(String repoRelativePath) throws Exception {
        Path p = Paths.get("..", repoRelativePath);
        if (!Files.exists(p)) {
            p = Paths.get(repoRelativePath);
        }
        return new String(Files.readAllBytes(p), "UTF-8");
    }

    /** 断言「某个文件已被拆除且没复活」用;两个候选路径的理由同 read()。 */
    static boolean exists(String repoRelativePath) {
        return Files.exists(Paths.get("..", repoRelativePath)) || Files.exists(Paths.get(repoRelativePath));
    }
}
