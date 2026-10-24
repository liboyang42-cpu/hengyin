#!/usr/bin/env python3
"""Bounded source inventory/body checks. This does not compile Java or prove business equivalence."""
import argparse, hashlib, json, pathlib, re


def mask(text):
    return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), text)


def methods(text):
    clean = mask(text)
    found = {}
    pat = r'(?m)^    (?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>, ?\[\].]+?\s+(\w+)\s*\(([^;{}]*)\)\s*(?:throws\s+[\w., ]+)?\{'
    for m in re.finditer(pat, clean):
        depth, end = 1, m.end()
        while depth:
            depth += (clean[end]=='{')-(clean[end]=='}'); end += 1
        found[(m[1], ' '.join(m[2].split()))] = text[m.end():end-1]
    return found


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-root',type=pathlib.Path,required=True)
    p.add_argument('--candidate-root',type=pathlib.Path,required=True)
    a=p.parse_args(); base=a.base_root; root=a.candidate_root
    pinned=json.loads(pathlib.Path(__file__).with_name('base-sources.json').read_text())
    assert pinned['base_sha']=='91962c94af463cbfe9b4d4406f9d88d269b097e4'
    for item in pinned['files']:
        raw=(base/item['path']).read_bytes()
        assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==item['blob'],item['path']
        assert hashlib.sha256(raw).hexdigest()==item['sha256'],item['path']
    prod='chengyinhub-system/src/main/java/com/chengyinhub/business/service/'
    source=(base/(prod+'impl/CmsTopicServiceImpl.java')).read_text()
    data=source.encode();assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()=='e7b9f22805e8d28292b46e36c68c62e5d5a1d41a'
    original=methods(source); facade_text=(root/(prod+'impl/CmsTopicServiceImpl.java')).read_text();facade=methods(facade_text)
    app_text=(root/(prod+'impl/TopicContentGraphAuthoringServiceImpl.java')).read_text();app=methods(app_text)
    guard=methods((root/(prod+'support/TopicContentWriteGuard.java')).read_text())
    reader=methods((root/(prod+'support/TopicRouteOutcomeReader.java')).read_text())
    graph=set('saveChaptersAndNodes saveTopicChapters shouldCompileChapterFlow compileChapterFlow saveNodes existingNodeUnlocks selectHiddenNodeIds restoreHiddenNodes restoreNodeUnlocks deleteTopicChapterFlow persistJourneyStoryAfterChapterRebuild remapRebuiltTemplateNodes persistTemplateInteractionRules saveRouteDefinition containsNodeKey copyTemplateForOwner'.split())
    guards=set('assertChapterShapeForTopic assertChapterShape assertAllowedValidationMethods assertTemplateOwned assertLegacyChapterFlowWritable assertBranchGraphOnlyForOrienteering'.split())
    shared={'declaredRouteOutcomes','assertBranchGraphOnlyForOrienteering'}
    owned=graph|guards|{'declaredRouteOutcomes'}
    changed={'createTopicInternal','rebuildTopicContent','updateTopicByOwnerInternal','deleteTopicEditableChildren'}|shared
    removed={key for key in original if key[0] in owned-shared}
    assert set(facade)==set(original)-removed
    unchanged=0
    for key,body in original.items():
        if key in removed or key[0] in changed:continue
        assert facade[key]==body,('unowned facade method changed',key)
        unchanged+=1
    assert 'RouteNodeMaps' not in facade_text
    assert 'class Builder' in app_text
    assert facade[('declaredRouteOutcomes','Long topicId')]=='\n        return routeOutcomeReader.declaredRouteOutcomes(topicId);\n    '
    assert facade[('assertBranchGraphOnlyForOrienteering','String routeMode, Integer productType')]=='\n        contentWriteGuard.assertBranchGraphOnlyForOrienteering(routeMode, productType);\n    '
    for name in ['createTopic','createTopicV2','createTopicV2WithReview','updateTopicByOwner','updateTopicByOwnerV2','updateTopicByOwnerV2WithReview']:
        assert re.search(r'@Transactional\(rollbackFor = Exception.class\)\s+public\s+[\w.]+\s+'+name+r'\(',facade_text),name
    assert 'REQUIRES_NEW' not in app_text
    for cls in ['TopicContentWriteGuard','TopicRouteOutcomeReader']:
        assert '@Transactional' not in (root/(prod+'support/'+cls+'.java')).read_text()
    def record_bodies(raw):
        clean=mask(raw);result={}
        for match in re.finditer(r'(?m)^        private void (record|recordChapter)\([^{}]*\) \{',clean):
            end=match.end();depth=1
            while depth:
                depth+=(clean[end]=='{')-(clean[end]=='}');end+=1
            result[match[1]]=raw[match.end():end-1]
        return result
    assert record_bodies(source)==record_bodies(app_text)
    assert len(record_bodies(source))==2
    moved=0
    for (name,args),body in original.items():
        if name not in owned:continue
        if name in guards:
            assert guard[(name,args)]==body,('guard body changed',name,args)
        elif name=='declaredRouteOutcomes':
            expected=body.replace('        return result;','        Map<Long, Set<String>> frozen = new LinkedHashMap<>();\n        result.forEach((node, values) -> frozen.put(node, Collections.unmodifiableSet(new LinkedHashSet<>(values))));\n        return Collections.unmodifiableMap(frozen);')
            assert reader[(name,args)]==expected
        else:
            expected=body.replace('RouteNodeMaps','Builder')
            for n in guards:expected=re.sub(r'(?<![\w.])'+n+r'\(', 'contentWriteGuard.'+n+'(',expected)
            expected=expected.replace('declaredRouteOutcomes(topicId)','routeOutcomeReader.declaredRouteOutcomes(topicId)')
            if name=='saveTopicChapters':expected=expected.replace('return saveChaptersAndNodes(chapters, topicId, username, memberId, allowBundledDraft);','return saveChaptersAndNodes(chapters, topicId, username, memberId, allowBundledDraft).freeze();')
            if name=='compileChapterFlow':expected=expected.replace('return routeNodeMaps;','return routeNodeMaps.freeze();').replace('new JourneyStory.EndingChapter(','new TopicAuthoredGraphIds.EndingChapterSnapshot(')
            if name=='persistJourneyStoryAfterChapterRebuild':expected=expected.replace('routeNodeMaps.endingChapters','routeNodeMaps.endingChapters()')
            if name=='existingNodeUnlocks':expected=expected.replace('return unlocks;','return Collections.unmodifiableMap(unlocks);')
            if name=='selectHiddenNodeIds':expected=expected.replace('return hidden;','return Collections.unmodifiableSet(hidden);')
            mapped=args.replace('RouteNodeMaps','TopicAuthoredGraphIds' if name in {'restoreNodeUnlocks','persistJourneyStoryAfterChapterRebuild','saveRouteDefinition'} else 'Builder')
            assert app[(name,mapped)]==expected,('graph body changed',name,args)
        moved+=1
    for key in original:
        name,args=key
        if name not in changed-shared:continue
        restored=facade[key]
        for n in graph:restored=restored.replace('contentGraphAuthoringService.'+n+'(',n+'(')
        restored=restored.replace('contentWriteGuard.assertLegacyChapterFlowWritable(','assertLegacyChapterFlowWritable(')
        restored=restored.replace('com.chengyinhub.business.service.support.TopicAuthoredGraphIds','RouteNodeMaps')
        for op,msg in [('Created','主题运行配置保存失败'),('Rebuilt','主题运行配置重映射失败')]:
            old='            contentGraphAuthoringService.persist'+op+'RuntimeBindings(topicId, runtimeRaw, routeNodeMaps);'
            call='runtimeRaw,routeNodeMaps.oldToNew,routeNodeMaps.chapterOldToNew,routeNodeMaps.createdClientToNew' if op=='Created' else 'runtimeRaw, routeNodeMaps.oldToNew, routeNodeMaps.chapterOldToNew, routeNodeMaps.createdClientToNew'
            new='            CmsTopic runtimePatch = new CmsTopic(); runtimePatch.setId(topicId);\n            runtimePatch.setThemeRuntimeConfigJson(remapTemplateRuleIdentities(com.chengyinhub.business.service.support.ThemeRuntimeConfig.remapForNodeSave('+call+'),routeNodeMaps.oldToNew));\n            if (cmsTopicMapper.updateCmsTopic(runtimePatch) != 1) throw new IllegalStateException("'+msg+'");'
            restored=restored.replace(old,new)
        assert restored==original[key],('facade orchestration changed',key)
    for operation,caller,message in [('Created','createTopicInternal','主题运行配置保存失败'),('Rebuilt','rebuildTopicContent','主题运行配置重映射失败')]:
        outer=next(body for (name,args),body in original.items() if name==caller)
        begin=outer.index('            CmsTopic runtimePatch = new CmsTopic(); runtimePatch.setId(topicId);')
        marker='throw new IllegalStateException("'+message+'");'
        end=outer.index(marker,begin)+len(marker)
        extracted=app[('persist'+operation+'RuntimeBindings','Long topicId, String runtimeRaw, TopicAuthoredGraphIds ids')]
        extracted=re.sub(r'\bids\b','routeNodeMaps',extracted)
        normalize=lambda raw:[line.lstrip() for line in raw.splitlines() if line.strip()]
        assert normalize(extracted)==normalize(outer[begin:end]),('runtime write changed',operation)
    for forbidden in ['ICmsTopicService','CmsTopicServiceImpl','TransactionTemplate','ApplicationContext','getBean(','Wechat','NotifyService','TopicSale','Settlement','Supplier<','Consumer<']:
        assert forbidden not in mask(app_text),forbidden
    # Relevant old cases may only change owner assembly / private reflection target.
    testdir='chengyinhub-admin/src/test/java/com/chengyinhub/business/'
    files=[*list((base/(testdir+'service')).rglob('*.java'))]
    chosen={'ChapterFlowWriteServiceTest.java','ChapterFlowTransactionTest.java','CmsTopicDraftBindingsTest.java','TopicUpdateByOwnerTest.java','TopicDraftPartialSaveGateTest.java','TopicDraftLegacyFieldPreservationTest.java','TopicJourneyStorySaveTest.java','TopicEditActivityShellTicketSyncChainTest.java','TopicDraftSubmittedOrderTest.java'}
    total=0
    for path in files:
        if path.name not in chosen:continue
        old=path.read_text();new=(root/path.relative_to(base)).read_text()
        restored=re.sub(r'com\.chengyinhub\.business\.service\.support\.TopicGraphTestWiring\.wire\((\w+)\)',r'\1',new)
        assert restored==old,('old test changed beyond assembly',path.name)
        total+=len(re.findall(r'@Test\b',old))
    assert total==141
    supplemental=json.loads(pathlib.Path(__file__).with_name('additional-test-callers.json').read_text())
    supplemental_cases=0
    for item in supplemental['files']:
        old=(base/item['path']).read_text();new=(root/item['path']).read_text()
        raw=old.encode()
        assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==item['base_blob'],item['path']
        assert hashlib.sha256(raw).hexdigest()==item['base_sha256'],item['path']
        restored=re.sub(r'com\.chengyinhub\.business\.service\.support\.TopicGraphTestWiring\.(?:wire|graph|guard|shared)\(([\w.]+)\)',r'\1',new)
        assert restored==old,('supplemental test changed beyond owner',item['path'])
        assert len(re.findall(r'@Test\b',old))==item['tests']
        supplemental_cases+=item['tests']
    assert len(supplemental['files'])==21 and supplemental_cases==216
    for name in ['OWN_C03_05Test.java','OWN_C03_25Test.java']:
        rel=pathlib.Path(testdir+'longrun')/name
        old=(base/rel).read_text();new=(root/rel).read_text()
        restored=new.replace('"com.chengyinhub.business.service.impl.TopicContentGraphAuthoringServiceImpl$Builder"',
                             'CmsTopicServiceImpl.class.getName() + "$RouteNodeMaps"')
        restored=restored.replace('invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.graph(service), "persistJourneyStoryAfterChapterRebuild", 700L, raw, ReflectionTestUtils.invokeMethod(maps, "freeze"), "creator")',
                                  'invokeMethod(service, "persistJourneyStoryAfterChapterRebuild", 700L, raw, maps, "creator")')
        restored=restored.replace('com.chengyinhub.business.service.support.TopicAuthoredGraphIds.EndingChapterSnapshot','JourneyStory.EndingChapter')
        assert restored==old,('builder seam test changed beyond owner',name)
    for file in [root/(prod+'impl/CmsTopicServiceImpl.java'),root/(prod+'impl/TopicContentGraphAuthoringServiceImpl.java'),root/(prod+'support/TopicContentWriteGuard.java'),root/(prod+'support/TopicRouteOutcomeReader.java'),root/(prod+'support/TopicAuthoredGraphIds.java')]:
        stack=[]
        for c in mask(file.read_text()):
            if c in '([{':stack.append(c)
            elif c in ')]}':assert stack and stack.pop()=={')':'(',']':'[','}':'{'}[c],file
        assert not stack,file
    print(json.dumps({'result':'PASS','pinned_source_files':len(pinned['files']),'unowned_facade_bodies_exact':unchanged,'moved_bodies_matched':moved,'facade_orchestrations_restored':4,'original_nine_class_cases':total,'supplemental_fixture_files':21,'supplemental_old_cases':supplemental_cases,'builder_seam_cases':9,'all_modified_file_old_cases_preserved':total+supplemental_cases+9,'old_builder_class_removed':True,'java_mysql_executed':False}))


if __name__=='__main__':main()
