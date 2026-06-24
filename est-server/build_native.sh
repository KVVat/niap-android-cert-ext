#!/bin/bash
set -euo pipefail

echo "=== Starting Native Build of libest ==="

mkdir -p build
cd build

if [ ! -d "libest" ]; then
    git clone --depth 1 --branch main https://github.com/cisco/libest.git libest
fi

cd libest
git rev-parse HEAD > ../LIBEST_COMMIT
echo "Cloned libest at commit: $(cat ../LIBEST_COMMIT)"

# Apply SHA-384 patch
sed -i 's/^default_md[[:space:]]*=.*/default_md     = sha384/' example/server/estExampleCA.cnf
echo "=== SHA setting verification ==="
grep -n "default_md" example/server/estExampleCA.cnf

# Patch configure.ac to remove duplicate AM_INIT_AUTOMAKE
awk 'BEGIN{n=0} /AM_INIT_AUTOMAKE/{n++; if(n==2)next} {print}' configure.ac > configure.ac.patched
mv configure.ac.patched configure.ac

# Run autotools
autoreconf --force --install --verbose 2>&1 | tail -5
./configure --disable-safec CFLAGS="-fsigned-char -Wno-error=implicit-function-declaration -Wno-error=deprecated-declarations"

# Build safe_c_stub
make -j"$(nproc)" -C safe_c_stub

# Inject FIPS compat stubs
printf 'int FIPS_mode_set(int r){(void)r;return 0;}\nint FIPS_mode(void){return 0;}\n' > fips_compat.c
gcc -c -o fips_compat.o fips_compat.c
ar rcs safe_c_stub/lib/libsafe_lib.a fips_compat.o
echo "=== FIPS compat stubs injected into libsafe_lib.a ==="

# Build src and server
make -j"$(nproc)" -C src
make -j"$(nproc)" -C example/server

echo "=== Build artifact ==="
ls -lh example/server/estserver
file example/server/estserver

echo "Native build complete! Binary is at build/libest/example/server/.libs/estserver"
