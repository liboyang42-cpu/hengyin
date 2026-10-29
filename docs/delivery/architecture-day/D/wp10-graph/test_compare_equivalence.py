"""Synthetic Python checks of receipt rejection. These are NOT Java/MySQL/equivalence evidence."""
import contextlib,copy,io,json,pathlib,runpy,sys,tempfile,unittest

HERE=pathlib.Path(__file__).resolve().parent
MODULE=runpy.run_path(str(HERE/'compare_equivalence.py'))
NAMES=json.loads((HERE/'mysql-cases.json').read_text())


class ReceiptComparatorRejectionTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root=pathlib.Path(self.temp.name);self.old=root/'old';self.new=root/'new'
        self.old.mkdir();self.new.mkdir()
        for name,scenarios in zip(NAMES,MODULE['EXPECTED_SCENARIOS']):
            common={'caseName':name,'outcomes':{s:{'return':'synthetic'} for s in scenarios},
                    'protectedRowsVerified':True,'originalRows':{'fixture':[{'id':1,'value':'original'}]},
                    'tables':{'fixture':[{'id':1,'value':'original'}]},'orderedBoundaryTrace':['CASE:synthetic','END:synthetic'],
                    'transactionFacts':[],'mapperStatements':[]}
            for folder,owner in [(self.old,'legacy'),(self.new,'extracted')]:
                (folder/(name+'.json')).write_text(json.dumps(dict(common,owner=owner)))

    def check(self):
        previous=sys.argv
        try:
            sys.argv=['compare_equivalence.py','--legacy',str(self.old),'--extracted',str(self.new)]
            with contextlib.redirect_stdout(io.StringIO()):MODULE['main']()
        finally:sys.argv=previous

    def mutate(self,change):
        p=self.new/(NAMES[0]+'.json');value=json.loads(p.read_text());change(value);p.write_text(json.dumps(value))

    def testCompleteSyntheticPairIsAccepted(self):self.check()
    def testMissingCaseIsRejected(self):
        (self.new/(NAMES[0]+'.json')).unlink()
        with self.assertRaises(AssertionError):self.check()
    def testUnexpectedCaseIsRejected(self):
        (self.new/'unexpected.json').write_text('{}')
        with self.assertRaises(AssertionError):self.check()
    def testWrongOwnerCannotRelabelSameSource(self):
        self.mutate(lambda v:v.update(owner='legacy'))
        with self.assertRaises(AssertionError):self.check()
    def testMissingSubscenarioIsRejected(self):
        self.mutate(lambda v:v.update(outcomes={}))
        with self.assertRaises(AssertionError):self.check()
    def testChangedFullRowIsRejected(self):
        self.mutate(lambda v:v['tables']['fixture'][0].update(value='changed'))
        with self.assertRaises(AssertionError):self.check()
    def testChangedMapperOrderIsRejected(self):
        self.mutate(lambda v:v.update(mapperStatements=['insert','delete']))
        with self.assertRaises(AssertionError):self.check()
    def testDifferentRootExceptionIsRejected(self):
        self.mutate(lambda v:v['outcomes'].update({next(iter(v['outcomes'])):{'class':'different','message':'different'}}))
        with self.assertRaises(AssertionError):self.check()
    def testIncompleteTransactionTraceIsRejected(self):
        self.mutate(lambda v:v.update(transactionFacts=[True]))
        with self.assertRaises(AssertionError):self.check()
    def testBooleanCannotMasqueradeAsInteger(self):
        self.mutate(lambda v:v['tables']['fixture'][0].update(id=True))
        with self.assertRaises(AssertionError):self.check()
    def testFractionLexemeIsNotSilentlyRounded(self):
        old=self.old/(NAMES[0]+'.json');new=self.new/(NAMES[0]+'.json')
        for path,value in [(old,'1.00'),(new,'1.0')]:
            text=path.read_text().replace('"value": "original"','"value": '+value)
            path.write_text(text)
        with self.assertRaises(AssertionError):self.check()


if __name__=='__main__':unittest.main()
