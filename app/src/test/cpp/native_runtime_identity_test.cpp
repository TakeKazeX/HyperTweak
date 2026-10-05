#include "native_runtime_identity.h"
#include <assert.h>
#include <string.h>
int main() {
    const auto record = hypertweak::native::RuntimeIdentity(444, 6455, 0x1000001f4ull);
    assert(record.schema == 1 && record.version == 444 && record.pid == 6455 && record.ready == 1);
    assert(record.start_ticks == 0x1000001f4ull);
    assert(record.checksum == (0x48544e53u ^ 444u ^ 6455u ^ 500u ^ 1u));
    const unsigned char expected[] = {1,0,0,0, 188,1,0,0, 55,25,0,0, 1,0,0,0,
                                    244,1,0,0, 1,0,0,0};
    assert(memcmp(&record, expected, sizeof(expected)) == 0);
    return 0;
}
