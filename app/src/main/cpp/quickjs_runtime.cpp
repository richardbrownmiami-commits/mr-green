#include "quickjs_runtime.h"
#include "quickjs.h"
#include <chrono>
#include <stdexcept>
#include <algorithm>
#include <cstring>

namespace {
struct State { QuickJsRuntime::Host host; int calls=0; bool quota=false; std::chrono::steady_clock::time_point deadline; };
int interrupt(JSRuntime*, void* opaque) { return std::chrono::steady_clock::now() >= static_cast<State*>(opaque)->deadline; }
JSValue host_call(JSContext* ctx, JSValueConst, int argc, JSValueConst* argv, int, JSValue* data) {
 auto* s=static_cast<State*>(JS_GetContextOpaque(ctx));
 if(!s || std::chrono::steady_clock::now()>=s->deadline) return JS_ThrowInternalError(ctx,"deadline");
 if(++s->calls>50){s->quota=true;return JS_ThrowInternalError(ctx,"host call limit");}
 const char* method=JS_ToCString(ctx,data[0]); if(!method)return JS_EXCEPTION;
 std::string args="[";
 for(int i=0;i<argc;i++){if(i)args+=",";JSValue j=JS_JSONStringify(ctx,argv[i],JS_UNDEFINED,JS_UNDEFINED);if(JS_IsUndefined(j)){JS_FreeValue(ctx,j);args+="null";continue;}const char*p=JS_ToCString(ctx,j);if(!p){JS_FreeValue(ctx,j);JS_FreeCString(ctx,method);return JS_EXCEPTION;}args+=p;JS_FreeCString(ctx,p);JS_FreeValue(ctx,j);if(args.size()>65536){JS_FreeCString(ctx,method);return JS_ThrowInternalError(ctx,"payload limit");}}
 args+="]";if(args.size()>65536){JS_FreeCString(ctx,method);return JS_ThrowInternalError(ctx,"payload limit");}
 try{auto result=s->host(method,args);JS_FreeCString(ctx,method);if(std::chrono::steady_clock::now()>=s->deadline)return JS_ThrowInternalError(ctx,"deadline");if(result.size()>65536)return JS_ThrowInternalError(ctx,"result limit");return JS_ParseJSON(ctx,result.c_str(),result.size(),"host");}catch(...){JS_FreeCString(ctx,method);return JS_ThrowInternalError(ctx,"host call failed");}
}
void add_api(JSContext*c,JSValue root,const char*object,const char*key,const char*method,int argc){
 JSValue o;if(!object[0])o=JS_DupValue(c,root);else{o=JS_GetPropertyStr(c,root,object);if(JS_IsUndefined(o)){o=JS_NewObject(c);JS_SetPropertyStr(c,root,object,JS_DupValue(c,o));}}
 JSValue d=JS_NewString(c,method);JSValue fn=JS_NewCFunctionData(c,host_call,argc,0,1,&d);JS_FreeValue(c,d);JS_SetPropertyStr(c,o,key,fn);JS_FreeValue(c,o);
}
}
std::string QuickJsRuntime::run(const std::string&source,const Host&host,int timeout_ms){
 if(source.size()>1024*1024||source.find("import")!=std::string::npos)throw std::runtime_error("script rejected");
 State s{host,0,false,std::chrono::steady_clock::now()+std::chrono::milliseconds(std::clamp(timeout_ms,1,4000))};
 JSRuntime*rt=JS_NewRuntime();if(!rt)throw std::runtime_error("runtime unavailable");JS_SetMemoryLimit(rt,16*1024*1024);JS_SetMaxStackSize(rt,512*1024);JS_SetInterruptHandler(rt,interrupt,&s);
 JSContext*ctx=JS_NewContext(rt);if(!ctx){JS_FreeRuntime(rt);throw std::runtime_error("runtime unavailable");}JS_SetContextOpaque(ctx,&s);
 JSValue global=JS_GetGlobalObject(ctx);
 add_api(ctx,global,"tool","call","tool.call",2);add_api(ctx,global,"mem","get","mem.get",1);add_api(ctx,global,"mem","set","mem.set",2);add_api(ctx,global,"","log","log",1);add_api(ctx,global,"http","get","http.get",1);add_api(ctx,global,"ai","ask","ai.ask",2);add_api(ctx,global,"time","now","time.now",0);add_api(ctx,global,"","notify","notify",1);
 const char* boot="Object.defineProperty(Object.getPrototypeOf(function(){}),'constructor',{value:undefined});Object.defineProperty(Object.getPrototypeOf(async function(){}),'constructor',{value:undefined});Object.defineProperty(Object.getPrototypeOf(function*(){}),'constructor',{value:undefined});Object.defineProperty(Object.getPrototypeOf(async function*(){}),'constructor',{value:undefined});";
 JSValue bootval=JS_Eval(ctx,boot,strlen(boot),"<bootstrap>",JS_EVAL_TYPE_GLOBAL);if(JS_IsException(bootval)){JS_FreeValue(ctx,bootval);JS_FreeValue(ctx,global);JS_FreeContext(ctx);JS_FreeRuntime(rt);throw std::runtime_error("runtime unavailable");}JS_FreeValue(ctx,bootval);
 JS_SetPropertyStr(ctx,global,"eval",JS_UNDEFINED);JS_SetPropertyStr(ctx,global,"Function",JS_UNDEFINED);JS_FreeValue(ctx,global);
 JSValue val=JS_Eval(ctx,source.c_str(),source.size(),"plugin.js",JS_EVAL_TYPE_GLOBAL|JS_EVAL_FLAG_STRICT);
 if(JS_IsException(val)||s.quota){JS_FreeValue(ctx,val);JS_FreeContext(ctx);JS_FreeRuntime(rt);throw std::runtime_error("script failed");}
 JSValue json=JS_JSONStringify(ctx,val,JS_UNDEFINED,JS_UNDEFINED);JS_FreeValue(ctx,val);if(JS_IsUndefined(json)){JS_FreeValue(ctx,json);json=JS_NewString(ctx,"null");}
 const char*p=JS_ToCString(ctx,json);std::string out;if(p){out=p;JS_FreeCString(ctx,p);}JS_FreeValue(ctx,json);JS_FreeContext(ctx);JS_FreeRuntime(rt);
 if(s.quota||std::chrono::steady_clock::now()>=s.deadline||out.empty()||out.size()>65536)throw std::runtime_error("script failed");return out;
}
