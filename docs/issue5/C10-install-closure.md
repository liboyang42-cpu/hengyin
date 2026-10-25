# C10 install closure for hengyin #608

Commit `492ee204ff8f362f6e3034ad4e46eabfa7dc0234`. Installing only the two files in that commit does not compile. `H5SessionPrimitiveLanding` constructs `WorkshopPackageVersionPublish` and `WorkshopInstallUpgradeRollback`. Those two classes construct `WorkshopOriginalRequestBinder`. `WorkshopInstallUpgradeRollback` also reads `WorkshopModuleImportGate`.

These four types are already on this branch. They are not in the two-file diff. Blob SHA is `git rev-parse HEAD:<path>`.

| path | blob | module |
|---|---|---|
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/workshop/WorkshopPackageVersionPublish.java` | `80be79da84dcdc09d8b088309c258b7e45b58f4f` | chengyinhub-system |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/workshop/WorkshopInstallUpgradeRollback.java` | `6aa58c5e3b7f85a971c7178a4807e91bc97bd195` | chengyinhub-system |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/workshop/WorkshopOriginalRequestBinder.java` | `a119dcdc3d9eb9519cebdf7db50ad5d6ee4714f0` | chengyinhub-system |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/workshop/WorkshopModuleImportGate.java` | `8a54ee626b5fac3b38298f83fe4d9aea891a21d6` | chengyinhub-system |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/workshop/H5SessionPrimitiveLanding.java` | `d9339b256f0c1eb3e7773c1fd54496d0e0d221cb` | chengyinhub-system |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/workshop/H5SessionPrimitiveLandingTest.java` | `a7a2eb8bb3c4971f5edff455ab483d9c9c0d2388` | chengyinhub-admin |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/workshop/WorkshopPackageVersionPublishTest.java` | `c9e306a9e60497e01dfa3e3b2d23af90aedc5314` | chengyinhub-admin |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/workshop/WorkshopInstallUpgradeRollbackTest.java` | `1919d2d84b34cd3eb18ae9576d3fdbcfafeed687` | chengyinhub-admin |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/workshop/WorkshopModuleImportGateTest.java` | `2b11c138d5be7f1da69f082294b233c09d0c6fcb` | chengyinhub-admin |

`WorkshopPackageVersionPublish` and `WorkshopInstallUpgradeRollback` import only `java.util`. Their other types are in this same package. This list does not include `ModCommerceEngine` or `ModInstallationService`. Those stay on the private source. This list does not issue a ticket.
