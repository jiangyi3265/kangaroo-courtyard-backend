# -*- coding: utf-8 -*-
"""Fail-closed source scan; original --self-test and no-argument CLI preserved.
No secrets/snippets are printed. Explicit reviewed findings require exact bytes.
"""
import importlib.util,sys,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SECURITY=ROOT/'acceptance/security'
REVIEW_SHA='df4220fa688004f858600987ba52fbdb7ceade81f62f67616302e77cdb997efd'
def load(name):
    spec=importlib.util.spec_from_file_location('security_'+name,SECURITY/(name+'.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def self_test():
    scanner=load('strict_scan');samples=load('legacy_samples');failures=[]
    for index,(rel,line)in enumerate(samples.SELF_TEST_MUST_FLAG):
        if not scanner.findings(rel,line):failures.append('must-flag-'+str(index))
    for index,(rel,line)in enumerate(samples.SELF_TEST_MUST_PASS):
        if scanner.findings(rel,line):failures.append('must-pass-'+str(index))
    if failures:
        print('Legacy contract failure: '+','.join(failures));return 1
    print('Original self-test contracts: 10/10')
    suite=unittest.defaultTestLoader.loadTestsFromModule(load('test_strict_scan'))
    return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful()else 1

def main():
    if sys.argv[1:]==['--self-test']:return self_test()
    return load('strict_scan').repository_main(ROOT,sys.argv[1:],REVIEW_SHA)

if __name__=='__main__':raise SystemExit(main())
