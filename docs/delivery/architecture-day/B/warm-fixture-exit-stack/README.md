# warm transport fixture静态嵌套修补 SOURCE

父提交：`c1554d6d93fd396ea7b4cb7a4f8041a7cc697fa5`；测试前像blob `654a55cd1fc144eb694ae3a011af76d6d69c80c9`。

`warm_transport_control`原逗号with含18个patch上下文，叠加原外层TemporaryDirectory/ExitStack和try/except超过Python静态嵌套限制。错误在模块导入时发生，不能记作JUnit、Maven或业务失败。

只将这18个上下文按原顺序交已有标准库contextlib.ExitStack.enter_context；没有预先构造整组patch，没有删除或替换patch。进入失败时已进入项仍逆序退出，原body异常处理、外层cleanup回调、report/failure和return求值位置保持。无需新import或调用者接口修改；全部原warm_transport_control调用者继续原签名，原125个测试方法及所有断言保留，不加镜像式测试。

只读核对本包新增的check_cache_receipt、preparation/content/compiler/manifest相关fixture上下文；未见同样超限长链，不扩大改动。生产Python/helper、C1/stub、C三Java、POM、CI和其它owner均未改。

仅源码读取/编辑、Git差分和运输摘要。parse/import/py_compile/测试/compile、Java/Maven/DB/Docker/HTTP/CI均 **NOT_RUN**。现有控制的执行成绩不转签此修补；不宣称125/220通过、内存收益或业务已运行。
