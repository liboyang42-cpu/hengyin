#!/usr/bin/env python3
"""Compare actual old/new graph receipts after BOTH source-bound XML32/CLEAN checks.
No Java execution and no implicit acceptance of a failed/partial run.
"""
import argparse,json,pathlib
from dataclasses import dataclass


@dataclass(frozen=True)
class Fraction:
    raw:str


def exact(value):
    if isinstance(value,dict):return ('object',tuple((key,exact(item)) for key,item in value.items()))
    if isinstance(value,list):return ('array',tuple(exact(item) for item in value))
    if isinstance(value,Fraction):return ('fraction',value.raw)
    if value is None:return ('null',)
    return (type(value).__name__,value)

EXPECTED_SCENARIOS=[
 ['create-v1'],['full-v2-create'],['free-without-blocks'],['free-with-blocks'],['v1-rebuild'],
 ['foreign-template-after-collab-delete'],['v2-rebuild'],['v2-legacy-rebuild'],['cloned-sys-node-template'],
 ['v1-template-reject-after-chapter','deleted-template','unapproved-template'],['simple-private-template-rejected','private-template-allowed-in-draft-bundle'],
 ['omitted-story'],['missing-unlock','hidden-and-unlock'],['runtime-bindings'],['duplicate-client-key','duplicate-old-id','long-client-key'],
 ['advanced-precedence','d20-reader','preference-reader','default-reader','invalid-reader-json'],
 ['perk-no-owner-read','revshare-owner-read'],['audio-first','category-reject','method-after-category','negative-node-xp'],
 ['club-direct-fee','zero-fee','fee-scale','perk-with-fee','zero-perk'],
 ['insertCmsTopicChapter','insertCmsTopicNode','insertCmsTopicChapter-missing-id','insertCmsTopicNode-missing-id'],['second-chapter'],['blocks-update'],
 ['updateCmsMemberTemplate','updateCmsTopicNode'],['story-update'],
 ['create-runtime','create-runtime-zero','rebuild-runtime-zero'],['expected-version','route-cas-zero'],
 ['real-compiler-null-noop','real-compiler-equal-noop','controlled-compiler-change','interaction-zero','runtime-dependency-absent'],
 ['xp-failure-continues'],['ticket-participant'],['review-participant'],['private-draft-create','private-draft-edit'],['caller-rollback']]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--legacy',type=pathlib.Path,required=True)
    p.add_argument('--extracted',type=pathlib.Path,required=True)
    a=p.parse_args()
    names=json.loads(pathlib.Path(__file__).with_name('mysql-cases.json').read_text())
    assert len(names)==len(EXPECTED_SCENARIOS)==32
    expected={name+'.json' for name in names}
    for folder in [a.legacy,a.extracted]:
        assert {f.name for f in folder.glob('*.json')}==expected,('missing/unexpected receipts',folder)
    scenarios=0
    for name,required in zip(names,EXPECTED_SCENARIOS):
        old=json.loads((a.legacy/(name+'.json')).read_text(),parse_float=Fraction)
        new=json.loads((a.extracted/(name+'.json')).read_text(),parse_float=Fraction)
        assert old.pop('owner')=='legacy'
        assert new.pop('owner')=='extracted'
        for payload in [old,new]:
            assert payload['caseName']==name
            assert set(payload['outcomes'])==set(required),('missing/unexpected scenarios',name)
            assert payload['protectedRowsVerified'] is True
            assert payload['originalRows'] and payload['tables']
            assert payload['orderedBoundaryTrace']
            assert isinstance(payload['transactionFacts'],list)
            assert len(payload['transactionFacts'])==sum(line.startswith('M:') for line in payload['orderedBoundaryTrace'])
            assert isinstance(payload['mapperStatements'],list)
            for scenario in required:
                result=payload['outcomes'][scenario]
                assert set(result)=={'return'} or set(result)=={'class','message'},(name,scenario)
        assert exact(old)==exact(new),('full graph rows/outcomes/ordered trace/transaction evidence differ',name)
        scenarios+=len(required)
    print(json.dumps({'result':'PASS','paired_cases':32,'paired_scenarios':scenarios,
                      'rows_ids_exceptions_mapper_and_boundary_order_equal':True,
                      'requires_independent_source_xml_cleanup_verification':True}))


if __name__=='__main__':main()
