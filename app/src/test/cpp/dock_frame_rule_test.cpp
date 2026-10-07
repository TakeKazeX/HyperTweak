#include "dock_frame_rule.h"
#include "dart_rule_support.h"
#include "lsposed_hook_backend.h"
#include <cassert>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>
#include <cstring>
#include <cstdarg>
#include <map>
#include <sys/socket.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
extern "C" volatile uint32_t g_dock_frame_enabled;
extern "C" uint64_t g_dock_frame_samples[8][7];
extern "C" uint32_t g_dock_frame_source_epoch;
extern "C" void HyperTweakDockVisibleReturn() {}
extern "C" void HyperTweakDockHiddenReturn() {}
extern "C" void HyperTweakDockControllerScaleReturn() {}
extern "C" void HyperTweakDockControllerAlphaReturn() {}
extern "C" void HyperTweakDockControllerSetReturn() {}
extern "C" void HyperTweakDockLocalTickReturn() {}
extern "C" void HyperTweakDockEditingReturn() {}
namespace {
std::vector<uint8_t> image;
hypertweak::native::DartResolution prepared{};
std::map<void*,std::vector<uint8_t>> originals;
int installs=0, fail_at=8, preparations=0;
}
int InstallInlineHook(void* target, void*, void** original) {
    ++installs;
    if (installs==fail_at) return kHookFailed;
    auto* p=static_cast<uint8_t*>(target);
    originals[target]=std::vector<uint8_t>(p,p+16);
    memset(target,0xcc,16); *original=reinterpret_cast<void*>(1);
    return kHookSuccess;
}
int RemoveInlineHook(void* target) {
    const auto found=originals.find(target); assert(found!=originals.end());
    memcpy(target,found->second.data(),16); originals.erase(found); return kHookSuccess;
}
namespace hypertweak::native {
void LogInfo(const char*,...) {}
void LogWarn(const char*,...) {}
void NotifyDockFramePreparation() { ++preparations; }
uintptr_t DartResolution::Find(const char* name) const {
    for(size_t i=0;i<site_count;++i) if(strcmp(sites[i].name,name)==0) return sites[i].address;
    return 0;
}
bool DartResolution::MatchVerify(const char* name) const {
    for(size_t i=0;i<site_count;++i) if(strcmp(sites[i].name,name)==0) {
        const auto& s=sites[i];return memcmp(reinterpret_cast<void*>(s.address+s.verify_delta),s.verify.bytes,s.verify.size)==0;
    }
    return false;
}
bool ResolveDartSites(void*,const dart::TargetSpec&,DartResolution* out) { *out=prepared;return prepared.located; }
}
int main(int argc,char** argv) {
    using namespace hypertweak::native;
    assert(argc==2); std::ifstream input(argv[1],std::ios::binary);
    image.assign(std::istreambuf_iterator<char>(input),{});
    dart::Image parsed{};assert(dart::ParseImage(image.data(),&parsed));
    const auto found=dart::ResolveTarget(parsed,dart::kDockFrameTarget);assert(found.resolved);
    prepared.base=image.data();prepared.located=true;prepared.site_count=6;
    for(size_t i=0;i<6;++i) {
        prepared.sites[i].name=dart::kDockFrameTarget.sites[i].name;
        prepared.sites[i].address=reinterpret_cast<uintptr_t>(image.data())+found.sites[i].offset;
        prepared.sites[i].verify=dart::kDockFrameTarget.sites[i].verify;
    }
    const auto listener=[](int* port){
        int fd=socket(AF_INET,SOCK_DGRAM,0);assert(fd>=0);
        sockaddr_in address{};address.sin_family=AF_INET;address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        assert(bind(fd,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0);
        socklen_t length=sizeof(address);assert(getsockname(fd,reinterpret_cast<sockaddr*>(&address),&length)==0);
        *port=ntohs(address.sin_port);fcntl(fd,F_SETFL,O_NONBLOCK);return fd;
    };
    int port1=0,port2=0;int first=listener(&port1),second=listener(&port2);
    for(size_t kind: {size_t{0},size_t{2},size_t{3}}) {
        g_dock_frame_samples[kind][0]=2;
        double values[]={0,0,.7,0,kind==2?0.:1.};
        memcpy(&g_dock_frame_samples[kind][1],values,sizeof(values));
        g_dock_frame_samples[kind][6]=kind+1;
    }
    const auto replay=[](int fd,int64_t token0,int64_t token1){
        for(size_t kind:{size_t{0},size_t{2},size_t{3}}) {
            uint64_t packet[9]{};pollfd ready{fd,POLLIN,0};assert(poll(&ready,1,1000)==1);assert(recv(fd,packet,sizeof(packet),0)==sizeof(packet));
            assert(packet[0]==(0x444b|(kind<<16)|(static_cast<uint64_t>(g_dock_frame_source_epoch)<<32)) && packet[1]==static_cast<uint64_t>(token0) && packet[2]==static_cast<uint64_t>(token1));
            assert(packet[3]==kind+1); // Replay retains source revision, never promotes stale state.
        }
    };
    assert(!ConfigureDockFrame(65536,101,-202));
    assert(!ConfigureDockFrame(10000,0,-202));
    assert(ConfigureDockFrame(port1,101,-202));
    assert(DockFrameRequested() && g_dock_frame_enabled==0);
    assert(!ApplyDockFrameRule(nullptr));assert(originals.empty() && g_dock_frame_enabled==0);
    fail_at=0;
    assert(ApplyDockFrameRule(nullptr));assert(originals.size()==8 && g_dock_frame_enabled==1);replay(first,101,-202);
    const int count=installs;
    const auto epoch=g_dock_frame_source_epoch;assert(epoch!=0);
    assert(ApplyDockFrameRule(nullptr));assert(installs==count);
    assert(ConfigureDockFrame(port2,303,-404));assert(g_dock_frame_enabled==0);
    assert(ApplyDockFrameRule(nullptr));assert(installs==count && g_dock_frame_enabled==1);replay(second,303,-404);
    auto* target=reinterpret_cast<uint8_t*>(prepared.sites[0].address);
    target[0]=0xab;
    assert(!ApplyDockFrameRule(nullptr));assert(target[0]==0xab && g_dock_frame_enabled==0);
    assert(ConfigureDockFrame(0,0,0));assert(!DockFrameRequested() && g_dock_frame_enabled==0);
    assert(ApplyDockFrameRule(nullptr));assert(target[0]==0xab);
    assert(preparations==3 && g_dock_frame_source_epoch==epoch);
    close(first);close(second);
    ResetDockFrameAfterFork();
    puts("PASS: complete-eight-site activation, partial rollback, idempotence, endpoint rebind, foreign ownership and disable");
}
