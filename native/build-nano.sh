#!/bin/sh
# Build XStreaming nano-rs (Xbox Remote Play engine) for Android. Usage: build-nano.sh <rust-triple> <clang-prefix>
set -e
export RUSTUP_HOME=/root/mcbuild/rust/rustup CARGO_HOME=/root/mcbuild/rust/cargo PATH=/root/mcbuild/rust/cargo/bin:$PATH
NDK=$(ls -d /root/mcbuild/sdk/ndk/* | tail -1); TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
T=$1; P=$2; U=$(echo $T | tr a-z- A-Z_); L=$(echo $T | tr - _)
export CARGO_TARGET_${U}_LINKER=$TC/${P}23-clang CC_$L=$TC/${P}23-clang AR_$L=$TC/llvm-ar
cd /root/mcbuild/xstreaming/nano-rs
nice -n 10 cargo build --target $T --release -j 6
ls -la target/$T/release/libnano_rs.so
