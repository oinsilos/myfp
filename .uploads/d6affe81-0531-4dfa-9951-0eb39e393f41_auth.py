import hashlib
import json
import random
import time

SIGN_SALT = "fu2s2kxcswgn5hqanx7asmlogyr5wu29"


def auth_signature(data):
    """为请求体生成带盐的 MD5 签名头,返回 (headers, body_str)。"""
    body_str = json.dumps(data, separators=(',', ':'), ensure_ascii=False)
    timestamp = int(time.time() * 1000)
    nonce = random.randint(1000, 9999)
    sign_str = f'{body_str}{nonce}{timestamp}{SIGN_SALT}'
    signature = hashlib.md5(sign_str.encode('utf-8')).hexdigest().upper()
    return {
        "Timestamp": str(timestamp),
        "Auth-Signature": signature,
        "Nonce": str(nonce),
        "System": "v1",
        "Content-Type": "application/json;charset=UTF-8"
    }, body_str