# 城瘾 CI 构建镜像 —— 云效 Flow 用
#
# 为什么要它:2026-08-27 把 12 个门禁从 GitHub Actions 搬到云效,踩到 9 类环境差异,
# 其中 5 类的共同病根是【每次跑都现装、装什么取决于当时的上游】:
#   · npm 从 10.8.2 自己滚到 10.9.8 → npm ci 报 "Exit handler never called",挂 18 分钟
#   · grype 的 install.sh 去问 GitHub "latest" → 撞上 302 / Empty reply,随机红
#   · dnf 给的 Node 是 20,而 ci/xcx-unit-manifest.js 硬要求 22+(fs.globSync)
#   · SetupJava(maven 3.6.3) 默认绑定 surefire 2.12.4,不认 -Dtest 逗号列表 → Tests run: 0
#   · MySQL 8.0.45 每轮下载 891MB
# 固化成带 tag 的镜像后,CI 从「每次赌上游」变成「可复现」。
#
# 版本全部来自实测,不是抄文档:
#   Node 22      ci/xcx-unit-manifest.js 硬性要求(fs.globSync,Node 21+)
#   npm 10.8.2   10.9.8 实测有 "Exit handler never called" bug
#   Python 3.11  ci/migration_gates.py 用 subprocess capture_output(3.7+)
#   JDK 11       与 GitHub 侧 temurin 11 对齐(产物字节要一致,否则指纹对不上)
#   Maven 3.9.3  云效枚举里可用的最新;默认绑定 surefire 3.x(3.9.6/3.8.8/3.8.6 被校验器拒)
#   MySQL 8.0.45 ci/run_migration.py 精确比对版本 —— 生产是宝塔自编译的 8.0.45,
#                seed 数据要逐字一致。dnf 只有 8.0.46,差一个补丁版就红,不能放宽。
#   gitleaks 8.21.2 / grype 0.87.0   钉死,不问 latest

FROM build-steps-public-registry.cn-beijing.cr.aliyuncs.com/build-steps/alinux3:latest

ARG NODE_MAJOR=22
ARG NPM_VER=10.8.2
ARG MAVEN_VER=3.9.3
ARG MYSQL_VER=8.0.45
ARG GITLEAKS_VER=8.21.2
ARG GRYPE_VER=0.87.0

# ── 基础工具 + MySQL 运行时依赖 + 字体 ─────────────────────────────────────
# libaio/numactl-libs: mysqld 启动必需
# fontconfig/dejavu:   某些测试(CmsTopicControllerScopeTest)会触到 java.awt,
#                      缺字体栈会 NoClassDefFoundError: sun.awt.X11FontManager
RUN dnf install -y --setopt=install_weak_deps=False \
      git curl tar xz which findutils \
      libaio numactl-libs fontconfig dejavu-sans-fonts \
      python3.11 python3.11-pip \
 && dnf clean all \
 && ln -sf /usr/bin/python3.11 /usr/local/bin/python3

# ── Node 22 + 钉死的 npm ───────────────────────────────────────────────────
RUN curl -fsSL https://rpm.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
 && dnf install -y nodejs && dnf clean all \
 && npm install -g npm@${NPM_VER} \
 && node -e 'process.exit(process.versions.node.split(".")[0]>='"${NODE_MAJOR}"'?0:1)'

# ── JDK 11 + Maven 3.9.3(surefire 3.x)───────────────────────────────────
RUN dnf install -y java-11-openjdk-devel && dnf clean all
RUN curl -fsSL -o /tmp/mvn.tgz \
      "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VER}/binaries/apache-maven-${MAVEN_VER}-bin.tar.gz" \
 && mkdir -p /opt/maven && tar xzf /tmp/mvn.tgz -C /opt/maven --strip-components=1 && rm /tmp/mvn.tgz
ENV MAVEN_HOME=/opt/maven
ENV PATH="/opt/maven/bin:${PATH}"
# 容器无 X11,强制 headless(见上面 fontconfig 那条的同一个坑)
ENV MAVEN_OPTS="-Djava.awt.headless=true"

# ── MySQL 8.0.45 二进制(精确版本,不用包管理器)────────────────────────────
# 云效跑在 K8s 上、docker daemon 不可用 ⇒ 不能用 mysql:8.0.45 容器;
# dnf 只有 8.0.46 ⇒ 只能下官方二进制。预置进镜像省掉每轮 891MB 下载。
RUN curl -fsSL -o /tmp/mysql.tar.xz \
      "https://mirrors.aliyun.com/mysql/MySQL-8.0/mysql-${MYSQL_VER}-linux-glibc2.28-x86_64.tar.xz" \
 && mkdir -p /opt/mysql && tar xJf /tmp/mysql.tar.xz -C /opt/mysql --strip-components=1 \
 && rm /tmp/mysql.tar.xz \
 && /opt/mysql/bin/mysqld --version | grep -q "${MYSQL_VER}" \
 && rm -rf /opt/mysql/mysql-test /opt/mysql/share/mysql-test
ENV PATH="/opt/mysql/bin:${PATH}"

# ── 安全扫描工具(钉死版本,不问 latest)────────────────────────────────────
RUN curl -fsSL -o /tmp/gl.tgz \
      "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VER}/gitleaks_${GITLEAKS_VER}_linux_x64.tar.gz" \
 && tar xzf /tmp/gl.tgz -C /usr/local/bin gitleaks && rm /tmp/gl.tgz \
 && curl -fsSL -o /tmp/grype.tgz \
      "https://github.com/anchore/grype/releases/download/v${GRYPE_VER}/grype_${GRYPE_VER}_linux_amd64.tar.gz" \
 && tar xzf /tmp/grype.tgz -C /usr/local/bin grype && rm /tmp/grype.tgz

# ── 自证:版本不对就构建失败,不带着错版本出厂 ──────────────────────────────
RUN set -e; \
    echo "=== 镜像自证 ==="; \
    node -v; npm -v; python3 -V; java -version 2>&1 | head -1; mvn -v | head -1; \
    mysqld --version; gitleaks version; grype version | head -2; \
    test "$(node -v | cut -c2- | cut -d. -f1)" = "${NODE_MAJOR}"; \
    test "$(npm -v)" = "${NPM_VER}"; \
    mvn -v | head -1 | grep -q "${MAVEN_VER}"; \
    mysqld --version | grep -q "${MYSQL_VER}"; \
    python3 -c 'import sys; assert sys.version_info>=(3,11)'; \
    echo "全部版本自证通过 ✅"

WORKDIR /workspace
