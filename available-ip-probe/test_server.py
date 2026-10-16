import json
import subprocess
import unittest
from unittest.mock import patch

import server


class AvailableIpProbeTest(unittest.TestCase):
    def test_skips_addresses_answering_arp_and_returns_first_unanswered(self):
        address_output = json.dumps([{
            "ifname": "ens160",
            "addr_info": [{"family": "inet", "local": "172.19.5.228", "prefixlen": 24}],
        }])
        scan_output = "172.19.5.1\t52:54:00:00:00:01\n172.19.5.2\t52:54:00:d6:26:96\n"

        def command_result(arguments, timeout):
            if arguments[:4] == ["ip", "-j", "-4", "addr"]:
                return subprocess.CompletedProcess(arguments, 0, address_output, "")
            if arguments[:3] == ["ip", "-j", "route"]:
                route_output = json.dumps([{
                    "dst": arguments[-1], "dev": "ens160", "prefsrc": "172.19.5.228",
                }])
                return subprocess.CompletedProcess(arguments, 0, route_output, "")
            if arguments[0] == "arp-scan":
                return subprocess.CompletedProcess(arguments, 0, scan_output, "")
            raise AssertionError(f"Unexpected command: {arguments}")

        with patch.object(server, "run_command", side_effect=command_result) as run_command:
            available_ip = server.find_available("172.19.5", 1, 5, 5000)

        self.assertEqual({
            "available_ip": "172.19.5.3",
            "responding_ips": ["172.19.5.1", "172.19.5.2"],
            "probe_method": "arp",
            "unknown_count": 0,
        }, available_ip)
        scan_call = next(call for call in run_command.call_args_list if call.args[0][0] == "arp-scan")
        self.assertIn("--interface=ens160", scan_call.args[0])
        self.assertIn("172.19.5.1", scan_call.args[0])
        self.assertIn("172.19.5.2", scan_call.args[0])

    def test_routed_network_uses_icmp_and_marks_nonresponders_as_candidates(self):
        address_output = json.dumps([{
            "ifname": "ens160",
            "addr_info": [{"family": "inet", "local": "172.19.5.228", "prefixlen": 24}],
        }])
        route_output = json.dumps([{
            "dst": "172.24.3.1", "dev": "ens160", "gateway": "172.19.5.1",
        }])

        def command_result(arguments, timeout):
            if arguments[:4] == ["ip", "-j", "-4", "addr"]:
                return subprocess.CompletedProcess(arguments, 0, address_output, "")
            if arguments[:3] == ["ip", "-j", "route"]:
                route_output = json.dumps([{
                    "dst": arguments[-1], "dev": "ens160", "gateway": "172.19.5.1",
                }])
                return subprocess.CompletedProcess(arguments, 0, route_output, "")
            if arguments[0] == "ping":
                is_responding = arguments[-1] == "172.24.3.2"
                return subprocess.CompletedProcess(arguments, 0 if is_responding else 1, "", "")
            raise AssertionError(f"Unexpected command: {arguments}")

        with patch.object(server, "run_command", side_effect=command_result) as run_command:
            result = server.find_available("172.24.3", 1, 3, 10000)

        self.assertEqual({
            "available_ip": "172.24.3.1",
            "responding_ips": ["172.24.3.2"],
            "probe_method": "icmp",
            "unknown_count": 2,
        }, result)
        self.assertEqual(3, sum(call.args[0][0] == "ping" for call in run_command.call_args_list))

    def test_ping_command_timeout_fails_probe(self):
        with patch.object(server, "run_command", side_effect=subprocess.TimeoutExpired("ping", 1)):
            with self.assertRaises(subprocess.TimeoutExpired):
                server.ping_candidate("172.24.3.9", 2)

    def test_range_with_mixed_route_targets_is_rejected(self):
        address_output = json.dumps([{
            "ifname": "ens160",
            "addr_info": [{"family": "inet", "local": "172.19.5.228", "prefixlen": 24}],
        }])

        def command_result(arguments, timeout):
            if arguments[:4] == ["ip", "-j", "-4", "addr"]:
                return subprocess.CompletedProcess(arguments, 0, address_output, "")
            if arguments[0:3] == ["ip", "-j", "route"]:
                gateway = arguments[-1].endswith(".2")
                route = {"dst": arguments[-1], "dev": "ens160"}
                if gateway:
                    route["gateway"] = "172.19.5.1"
                return subprocess.CompletedProcess(arguments, 0, json.dumps([route]), "")
            raise AssertionError(f"Unexpected command: {arguments}")

        with patch.object(server, "run_command", side_effect=command_result):
            with self.assertRaisesRegex(RuntimeError, "不同网络路由"):
                server.find_available("172.24.3", 1, 3, 10000)


if __name__ == "__main__":
    unittest.main()
