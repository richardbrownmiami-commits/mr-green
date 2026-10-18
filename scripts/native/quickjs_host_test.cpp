#include "quickjs_runtime.h"
#include <iostream>
#include <stdexcept>
#include <string>
#include <chrono>
int main(){int calls=0;std::string lastMethod,lastArgs;auto host=[&](const std::string&m,const std::string&a){++calls;lastMethod=m;lastArgs=a;return std::string("{\"ok\":true}");};
 auto check=[&](bool b,const char*n){if(!b)throw std::runtime_error(n);std::cout<<"PASS "<<n<<"\n";};
 check(QuickJsRuntime::run("({x:2})",host,4000)=="{\"x\":2}","normal result");
 check(QuickJsRuntime::run("tool.call('demo',{v:2})",host,4000)=="{\"ok\":true}"&&lastMethod=="tool.call"&&lastArgs=="[\"demo\",{\"v\":2}]","host method/args JSON contract");
 check(QuickJsRuntime::run("undefined",host,4000)=="null","undefined result serialized as null");
 check(QuickJsRuntime::run("[typeof std,typeof os,typeof Java,typeof require,typeof fetch]",host,4000)=="[\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"undefined\"]","no ambient modules/platform/network");
 for(auto code:{"(()=>{}).constructor('return 4')()","(async()=>{}).constructor('return 4')()","(function*(){}).constructor('return 4')()","(async function*(){}).constructor('return 4')()"}){bool blocked=false;try{QuickJsRuntime::run(code,host,1000);}catch(...){blocked=true;}check(blocked,"constructor eval blocked");}
 for(auto code:{"eval('1')","Function('return 1')()","import('x')"}){bool failed=false;try{QuickJsRuntime::run(code,host,1000);}catch(...){failed=true;}check(failed,"dynamic code/import blocked");}
 bool oom=false;try{QuickJsRuntime::run("let a=[];while(true)a.push(new Array(10000).fill('x'))",host,4000);}catch(...){oom=true;}check(oom,"heap limit");
 std::string many="try{";for(int i=0;i<51;i++)many+="tool.call('x',{});";many+="}catch(e){} 1";bool quota=false;try{QuickJsRuntime::run(many,host,4000);}catch(...){quota=true;}check(quota,"uncatchable 50-call quota");
 auto start=std::chrono::steady_clock::now();bool timed=false;try{QuickJsRuntime::run("while(true){}",host,4000);}catch(...){timed=true;}auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();check(timed&&elapsed<=5000,"infinite loop stopped within five seconds");
 bool stringifyTimed=false;try{QuickJsRuntime::run("({toJSON(){while(true){}}})",host,4000);}catch(...){stringifyTimed=true;}check(stringifyTimed,"deadline applies to result serialization");
}
