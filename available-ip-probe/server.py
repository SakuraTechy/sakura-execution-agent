import hmac
import ipaddress
import json
import os
import subprocess
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


TOKEN = os.environ.get("SAKURA_AGENT_TOKEN", "")
BIND_ADDRESS = os.environ.get("SAKURA_AVAILABLE_IP_PROBE_BIND", "192.172.0.1")
PORT = int(os.environ.get("SAKURA_AVAILABLE_IP_PROBE_PORT", "19092"))
ALLOWED_SOURCE = ipaddress.ip_network(
    os.environ.get("SAKURA_AVAILABLE_IP_PROBE_ALLOWED_SOURCE", "192.172.0.0/24"), strict=False
)
MAX_TIMEOUT_SECONDS = 30
MAX_ICMP_WORKERS = 64
ICMP_PING_TIMEOUT_SECONDS = 1


def run_command(arguments, timeout):
    return subprocess.run(arguments, check=False, capture_output=True, text=True, timeout=timeout)


def ping_candidate(candidate, timeout_seconds):
    result = run_command(["ping", "-n", "-c", "1", "-W", str(ICMP_PING_TIMEOUT_SECONDS), str(candidate)],
                         timeout_seconds)
    if result.returncode in (0, 1):
        return result.returncode == 0
    raise RuntimeError("宿主机 ICMP 探测命令失败")


def find_by_icmp(candidates, timeout_ms):
    worker_count = min(MAX_ICMP_WORKERS, len(candidates))
    waves = (len(candidates) + worker_count - 1) // worker_count
    timeout_seconds = max(ICMP_PING_TIMEOUT_SECONDS + 0.5, timeout_ms / 1000 / waves)
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = [executor.submit(ping_candidate, candidate, timeout_seconds) for candidate in candidates]
        responding = [future.result() for future in futures]
    responding_ips = [str(candidate) for candidate, is_responding in zip(candidates, responding) if is_responding]
    return {
        "available_ip": next((str(candidate) for candidate, is_responding in zip(candidates, responding)
                              if not is_responding), None),
        "responding_ips": responding_ips,
        "probe_method": "icmp",
        "unknown_count": len(candidates) - len(responding_ips),
    }


def find_available(prefix, start, end, timeout_ms):
    # 直连网段用 ARP 确认占用；经网关时 ICMP 无响应仅作为临时候选。
    network_prefix = ipaddress.IPv4Network(f"{prefix}.0/24", strict=False)
    candidates = [ipaddress.IPv4Address(f"{prefix}.{host}") for host in range(start, end + 1)]
    first_octets = [int(part) for part in prefix.split(".")]
    is_private_network = first_octets[0] == 10 or (first_octets[0] == 172 and 16 <= first_octets[1] <= 31) \
        or (first_octets[0] == 192 and first_octets[1] == 168)
    if not is_private_network:
        raise RuntimeError("只允许探测 RFC1918 私有网段")

    all_addresses = run_command(["ip", "-j", "-4", "addr", "show"], 1)
    if all_addresses.returncode != 0:
        raise RuntimeError("无法读取宿主机网卡地址")
    local_addresses = {
        ipaddress.IPv4Address(item["local"])
        for interface_info in json.loads(all_addresses.stdout)
        for item in interface_info.get("addr_info", [])
        if item.get("family") == "inet"
    }
    probe_candidates = [candidate for candidate in candidates if candidate not in local_addresses]
    if not probe_candidates:
        return {"available_ip": None, "responding_ips": [], "probe_method": "arp", "unknown_count": 0}

    def get_route(candidate):
        route = run_command(["ip", "-j", "route", "get", str(candidate)], 1)
        if route.returncode != 0:
            raise RuntimeError("无法查询目标路由")
        routes = json.loads(route.stdout)
        if len(routes) != 1 or not routes[0].get("dev"):
            raise RuntimeError("无法确定目标网络路由")
        return routes[0]

    route_workers = min(MAX_ICMP_WORKERS, len(probe_candidates))
    with ThreadPoolExecutor(max_workers=route_workers) as executor:
        routes = list(executor.map(get_route, probe_candidates))
    route_profiles = {(route["dev"], route.get("gateway")) for route in routes}
    if len(route_profiles) != 1:
        raise RuntimeError("探测范围跨越不同网络路由，请拆分探测范围")
    if routes[0].get("gateway"):
        return find_by_icmp(probe_candidates, timeout_ms)

    interface = routes[0]["dev"]
    target_network = None
    for interface_info in json.loads(all_addresses.stdout):
        if interface_info.get("ifname") != interface:
            continue
        for item in interface_info.get("addr_info", []):
            if item.get("family") != "inet":
                continue
            network = ipaddress.ip_network(f"{item['local']}/{item['prefixlen']}", strict=False)
            if probe_candidates[0] in network:
                target_network = network
                break
    if target_network is None or not network_prefix.subnet_of(target_network):
        raise RuntimeError("目标 IP 不属于宿主机网卡所在子网或为保留地址")
    if any(candidate in (target_network.network_address, target_network.broadcast_address)
           for candidate in probe_candidates):
        raise RuntimeError("候选范围包含网络地址或广播地址")

    timeout_seconds = max(1, min(MAX_TIMEOUT_SECONDS, (timeout_ms + 999) // 1000))
    command = ["arp-scan", f"--interface={interface}", "--retry=2", "--timeout=500", "--plain"]
    command.extend(str(candidate) for candidate in probe_candidates)
    result = run_command(command, timeout_seconds)
    if result.returncode != 0:
        raise RuntimeError("宿主机 ARP 扫描失败")
    active_addresses = set()
    for line in result.stdout.splitlines():
        columns = line.split()
        if columns:
            try:
                active_addresses.add(ipaddress.IPv4Address(columns[0]))
            except ipaddress.AddressValueError:
                continue
    responding_ips = [str(candidate) for candidate in candidates if candidate in active_addresses]
    available_ip = next((str(candidate) for candidate in candidates
                         if candidate not in active_addresses and candidate not in local_addresses), None)
    return {
        "available_ip": available_ip,
        "responding_ips": responding_ips,
        "probe_method": "arp",
        "unknown_count": 0,
    }


class ProbeHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def do_POST(self):
        if self.path != "/probe":
            self.send_error(404)
            return
        source_address = ipaddress.ip_address(self.client_address[0])
        if source_address not in ALLOWED_SOURCE:
            self.send_error(403)
            return
        supplied_token = self.headers.get("Authorization", "")
        if not TOKEN or not hmac.compare_digest(supplied_token, f"Bearer {TOKEN}"):
            self.send_error(401)
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
            if content_length < 1 or content_length > 1024:
                raise ValueError("请求长度非法")
            request = json.loads(self.rfile.read(content_length))
            prefix = str(ipaddress.IPv4Network(f"{request['prefix']}.0/24", strict=False).network_address).rsplit(".", 1)[0]
            if prefix != request["prefix"]:
                raise ValueError("IPv4 前缀非法")
            start = int(request["start"])
            end = int(request["end"])
            if start < 1 or end > 254 or start > end or end - start > 253:
                raise ValueError("探测范围非法")
            timeout_ms = int(request.get("timeout_ms", 1000))
            if timeout_ms < 100 or timeout_ms > 30000:
                raise ValueError("探测超时非法")
            probe_result = find_available(prefix, start, end, timeout_ms)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            self.send_error(400)
            return
        except (OSError, RuntimeError, subprocess.TimeoutExpired):
            self.send_error(503)
            return
        body = json.dumps(probe_result).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print("available-ip-probe", format % args, flush=True)


def main():
    if not TOKEN:
        raise SystemExit("SAKURA_AGENT_TOKEN is required")
    ThreadingHTTPServer((BIND_ADDRESS, PORT), ProbeHandler).serve_forever()


if __name__ == "__main__":
    main()
