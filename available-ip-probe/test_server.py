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
        route_output = json.dumps([{"dst": "172.19.5.1", "dev": "ens160", "prefsrc": "172.19.5.228"}])
        scan_output = "172.19.5.1\t52:54:00:00:00:01\n172.19.5.2\t52:54:00:d6:26:96\n"
        command_results = [
            subprocess.CompletedProcess([], 0, address_output, ""),
            subprocess.CompletedProcess([], 0, route_output, ""),
            subprocess.CompletedProcess([], 0, scan_output, ""),
        ]

        with patch.object(server, "run_command", side_effect=command_results) as run_command:
            available_ip = server.find_available("172.19.5", 1, 5, 5000)

        self.assertEqual({
            "available_ip": "172.19.5.3",
            "responding_ips": ["172.19.5.1", "172.19.5.2"],
        }, available_ip)
        self.assertIn("--interface=ens160", run_command.call_args_list[2].args[0])
        self.assertIn("172.19.5.1", run_command.call_args_list[2].args[0])
        self.assertIn("172.19.5.2", run_command.call_args_list[2].args[0])

    def test_rejects_target_reached_through_gateway(self):
        address_output = json.dumps([{
            "ifname": "ens160",
            "addr_info": [{"family": "inet", "local": "172.19.5.228", "prefixlen": 24}],
        }])
        route_output = json.dumps([{
            "dst": "172.19.5.1", "dev": "ens160", "gateway": "172.19.5.1",
        }])
        command_results = [
            subprocess.CompletedProcess([], 0, address_output, ""),
            subprocess.CompletedProcess([], 0, route_output, ""),
        ]

        with patch.object(server, "run_command", side_effect=command_results):
            with self.assertRaisesRegex(RuntimeError, "直连网络"):
                server.find_available("172.19.5", 1, 5, 5000)


if __name__ == "__main__":
    unittest.main()
