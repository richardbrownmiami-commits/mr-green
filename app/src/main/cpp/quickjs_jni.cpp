#include <jni.h>
#include "quickjs_runtime.h"
#include <string>
#include <stdexcept>
#include <vector>
static std::string utf8(JNIEnv* e,jstring v){
 if(!v)throw std::runtime_error("input");jsize n=e->GetStringLength(v);const jchar* p=e->GetStringChars(v,nullptr);if(!p)throw std::runtime_error("input");std::string out;out.reserve((size_t)n*3);
 for(jsize i=0;i<n;i++){uint32_t c=p[i];if(c>=0xD800&&c<=0xDBFF&&i+1<n&&p[i+1]>=0xDC00&&p[i+1]<=0xDFFF)c=0x10000+((c-0xD800)<<10)+(p[++i]-0xDC00);else if(c>=0xD800&&c<=0xDFFF)c=0xFFFD;
 if(c<0x80)out.push_back((char)c);else if(c<0x800){out.push_back((char)(0xC0|(c>>6)));out.push_back((char)(0x80|(c&63)));}else if(c<0x10000){out.push_back((char)(0xE0|(c>>12)));out.push_back((char)(0x80|((c>>6)&63)));out.push_back((char)(0x80|(c&63)));}else{out.push_back((char)(0xF0|(c>>18)));out.push_back((char)(0x80|((c>>12)&63)));out.push_back((char)(0x80|((c>>6)&63)));out.push_back((char)(0x80|(c&63)));}}
 e->ReleaseStringChars(v,p);return out;
}
static jstring fromUtf8(JNIEnv* e,const std::string&s){
 std::vector<jchar> out;
 for(size_t i=0;i<s.size();){uint8_t a=(uint8_t)s[i++];uint32_t c=0xFFFD;
  if(a<0x80)c=a;
  else if((a&0xE0)==0xC0&&i<s.size()){uint8_t b=(uint8_t)s[i++];if((b&0xC0)==0x80)c=((a&31)<<6)|(b&63);}
  else if((a&0xF0)==0xE0&&i+1<s.size()){uint8_t b=(uint8_t)s[i++],d=(uint8_t)s[i++];if((b&0xC0)==0x80&&(d&0xC0)==0x80)c=((a&15)<<12)|((b&63)<<6)|(d&63);}
  else if((a&0xF8)==0xF0&&i+2<s.size()){uint8_t b=(uint8_t)s[i++],d=(uint8_t)s[i++],f=(uint8_t)s[i++];if((b&0xC0)==0x80&&(d&0xC0)==0x80&&(f&0xC0)==0x80)c=((a&7)<<18)|((b&63)<<12)|((d&63)<<6)|(f&63);}
  if(c>0x10FFFF||(c>=0xD800&&c<=0xDFFF))c=0xFFFD;
  if(c>0xFFFF){c-=0x10000;out.push_back((jchar)(0xD800+(c>>10)));out.push_back((jchar)(0xDC00+(c&1023)));}else out.push_back((jchar)c);
 }
 return e->NewString(out.data(),(jsize)out.size());
}
extern "C" JNIEXPORT jstring JNICALL Java_im_mrgreen_plugins_QuickJsRuntime_nativeRun(JNIEnv* e,jobject,jstring src,jobject host,jlong timeout){
 try{jclass c=e->GetObjectClass(host);jmethodID m=e->GetMethodID(c,"invoke","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");if(!m)throw std::runtime_error("host");
 auto cb=[&](const std::string&method,const std::string&args){jstring jm=fromUtf8(e,method),ja=fromUtf8(e,args);jstring r=(jstring)e->CallObjectMethod(host,m,jm,ja);e->DeleteLocalRef(jm);e->DeleteLocalRef(ja);if(e->ExceptionCheck()){e->ExceptionClear();throw std::runtime_error("host");}if(!r)throw std::runtime_error("host");std::string out=utf8(e,r);e->DeleteLocalRef(r);return out;};
 return fromUtf8(e,QuickJsRuntime::run(utf8(e,src),cb,(int)timeout));
 }catch(...){if(e->ExceptionCheck())e->ExceptionClear();jclass x=e->FindClass("java/lang/IllegalStateException");e->ThrowNew(x,"Script execution failed");return nullptr;}
}
