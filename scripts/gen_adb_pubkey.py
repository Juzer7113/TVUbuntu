#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 App 内置私钥 assets/adb_key.pem 生成 Android adbd 的 adb_keys 格式公钥行。

算法与 App 端 AdbClient.buildAdbPublicKeyStatic 完全一致（AndroidPubkey 编码）：
  words   = (N.bitLength() + 31) // 32
  bytes   = words * 4
  n0inv   = (2^32 - (N mod 2^32)^-1) mod 2^32
  rr      = (2^(bytes*8) mod N)^2 mod N
  payload = LE[int32 words, int32 n0inv, bytes(N), bytes(rr), int32 exponent]
  line    = base64(payload) + " tvubuntu"

用法：
  python gen_adb_pubkey.py <adb_key.pem> <输出文件>
"""
import base64
import re
import subprocess
import sys


def le_bytes(value: int, size: int) -> bytes:
    big = value.to_bytes((value.bit_length() + 7) // 8, "big").lstrip(b"\x00")
    out = bytearray(size)
    out[: len(big)] = big[::-1]  # 大端去前导零后反转 = 小端
    return bytes(out)


def main():
    if len(sys.argv) < 3:
        print("usage: gen_adb_pubkey.py <adb_key.pem> <out_file>")
        sys.exit(1)
    pem = sys.argv[1]
    out_path = sys.argv[2]
    # modulus：openssl rsa -modulus 输出单行 "Modulus=HEX..."
    mod_out = subprocess.check_output(["openssl", "rsa", "-in", pem, "-noout", "-modulus"]).decode()
    m = re.search(r"Modulus=([0-9a-fA-F]+)", mod_out)
    if not m:
        raise RuntimeError("无法从 openssl 输出解析 modulus")
    modulus_hex = m.group(1)
    # exponent：从 -text 解析 publicExponent
    text = subprocess.check_output(["openssl", "rsa", "-in", pem, "-noout", "-text"]).decode()
    e = re.search(r"publicExponent:\s*(\d+)", text)
    exponent = int(e.group(1)) if e else 65537

    n = int(modulus_hex, 16)
    words = (n.bit_length() + 31) // 32
    bytes_ = words * 4
    r32 = 1 << 32
    n0 = n % r32
    n0inv = (r32 - pow(n0, -1, r32)) % r32
    rr = pow(pow(2, bytes_ * 8, n), 2, n)

    payload = (
        le_bytes(words, 4)
        + le_bytes(n0inv, 4)
        + le_bytes(n, bytes_)
        + le_bytes(rr, bytes_)
        + le_bytes(exponent, 4)
    )
    line = base64.b64encode(payload).decode() + " tvubuntu"
    with open(out_path, "w") as f:
        f.write(line + "\n")
    print("写入:", out_path)
    print(line)


if __name__ == "__main__":
    main()
