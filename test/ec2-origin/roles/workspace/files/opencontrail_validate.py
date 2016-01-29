#!/usr/bin/python

"""
Sanity check the status of an openshift + opencontrail cluster
"""

import ConfigParser
import argparse
import json
import paramiko
import re
import sys
import time
import xml.etree.ElementTree
from datetime import datetime


class Executor(object):
    DEFAULT_USERNAME = 'centos'

    def __init__(self, server):
        """ Constructor """
        self._ssh_client = paramiko.SSHClient()
        self._ssh_client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        self._ssh_client.connect(server, username=Executor.DEFAULT_USERNAME)

    def run(self, cmd, sudo=False):
        if sudo:
            cmd = 'sudo ' + cmd
        _, stdout, stderr = self._ssh_client.exec_command(cmd, get_pty=sudo)
        stdout.channel.recv_exit_status()
        return stdout.readlines(), stderr.readlines()

    def __enter__(self):
        return self

    def __exit__(self, type, value, traceback):
        self._ssh_client.close()

    def __del__(self):
        """ Destructor """
        self._ssh_client.close()


def expect_listen_ports(channel, expected):
    stdout, stderr = channel.run('netstat -ntl')

    absent = expected

    regexp = re.compile(r'(tcp\s+[0-9]+\s+[0-9]+\s+([0-9\.]+):([0-9]+)|'
                        'tcp6\s+[0-9]+\s+[0-9]+\s+:::([0-9]+))\s+(.*)'
                        '\s+LISTEN')
    for line in stdout:
        match = regexp.match(line)
        if match:
            if match.group(1).startswith('tcp6'):
                port = match.group(4)
            else:
                port = match.group(3)
            port = int(port)
            if port in absent:
                absent.pop(port)
    return absent


def expect_docker_running(channel, containerNames):
    stdout, stderr = channel.run("docker ps --format='{{.ID}} {{.Names}}'",
                                 sudo=True)
    regexp = re.compile(r'([a-f0-9]+)\s([\w-]+)')
    absent = containerNames

    for line in stdout:
        m = regexp.match(line)
        if not m:
            print "Unexpected output from ps command: %s" % line
            continue
        name = m.group(2)
        if name in absent:
            absent.remove(name)

    return absent


def contrail_services_status(channel):
    tcp_ports = {
        8082: "contrail-api",
        8444: "ifmap",
        5269: "xmpp",
        9160: "cassandra",
        2181: "zookeeper",
        5672: "rabbitmq"
    }
    absent = expect_listen_ports(channel, tcp_ports)
    if len(absent):
        print "Service ports not running:"
        print absent
        return False
    return True


def contrail_docker_status(channel, netManager=False):
    containerNames = [
        'contrail-control', 'contrail-api', 'contrail-schema', 'ifmap-server'
    ]
    if netManager:
        containerNames.append('kube-network-manager')
    absent = expect_docker_running(channel, containerNames)
    if len(absent) > 0:
        print "Containers not running: ", absent
        return False
    return True


def contrail_docker_agent(channel, nodeIP):
    absent = expect_docker_running(channel, ['vrouter-agent'])
    if len(absent) > 0:
        print 'vrouter agent node not running on %s' % nodeIP
        return False
    return True


def contrail_api_status(channel):
    stdout, stderr = channel.run('curl http://localhost:8082')
    try:
        json.loads('\n'.join(stdout))
    except Exception:
        print 'Unable to connect to the contrail-api server'
        print '\n'.join(stderr)
        return False
    return True


def contrail_control_instance_status(channel):
    """
    Verify that the control-node is not stuck with a deleted routing-instance.
    """
    stdout, stderr = channel.run(
        'curl http://localhost:8083/Snh_ShowRoutingInstanceSummaryReq')
    if len(stdout) == 0:
        print 'Unable to get routing instance summary'
        print '\n'.join(stderr)
        return False

    root = xml.etree.ElementTree.fromstringlist(stdout)
    count = 0
    for instance in root.findall('.//ShowRoutingInstance'):
        delete_tag = instance.find('deleted')
        if delete_tag.text == 'true':
            name = instance.find('name')
            print 'instance %s deleted' % name.text
            count += 1

    return count == 0


def contrail_xmpp_sessions(channel):
    """
    Wait for 180 secs for the sessions to come up.
    """
    for _ in range(18):
        stdout, stderr = channel.run(
            "netstat -nt | grep -E ':5269\s+.*ESTABLISHED'")
        if len(stdout) == 3:
            return True
        time.sleep(10)

    print 'XMPP sessions:'
    print '\n'.join(stdout)
    return False


def openshift_system_services(channel):
    """
    Ensure that openshift is able to start the docker-registry and router pods.
    This requires the deployer pods to be able to communicate with the master.
    """

    def patternInList(pattern, pods):
        regexp = re.compile(pattern)
        for pod in pods:
            if regexp.match(pod):
                return True
        return False

    expect = [r'docker-registry-([0-9]+)-', r'router-([0-9]+)-']

    for _ in range(36):
        stdout, stderr = channel.run("oc get pods -o json")
        data = json.loads('\n'.join(stdout))

        pods = []
        for item in data['items']:
            if item['status']['phase'] != 'Running':
                continue
            if 'generateName' in item['metadata']:
                pods.append(item['metadata']['generateName'])

        absent = []
        for pattern in expect:
            if not patternInList(pattern, pods):
                absent.append(pattern)
        if len(absent) == 0:
            return True
        time.sleep(10)

    print 'system pods not running'
    print absent
    stdout, stderr = channel.run("oc get pods")
    print '\n'.join(stdout)
    return False


def contrail_gateway_expect_svc_routes(channel, master, gatewayIP):
    """
    The unicast routing table for the service VRF should have routes for the
    system services.
    """

    stdout, stderr = master.run(
        "oc get svc -o jsonpath='{.items[*].spec.clusterIP}'")
    if len(stdout) == 0:
        print 'No service IPs'
        print '\n'.join(stderr)
        return False

    svc = stdout[0].split()
    if len(svc) < 3:
        print 'Expected at least 3 clusterIPs'
        return False

    stdout, stderr = channel.run("vif --list")

    re_section = re.compile(r'vif0\/([0-9]+)\s+OS:\s(\w+)')
    re_vrf = re.compile(r'Vrf:([0-9]+)')

    vrf_index = None
    inSection = False
    for line in stdout:
        m = re_section.match(line)
        if m:
            if m.group(2) == 'gateway1':
                inSection = True
                continue
            if inSection:
                break
        if not inSection:
            continue
        tag = re_vrf.search(line)
        if tag:
            vrf_index = int(tag.group(1))

    if not vrf_index:
        print 'Unable to determine vrf id'
        return False

    absent = svc
    stdout, stderr = channel.run(
        "curl http://localhost:8085/Snh_Inet4UcRouteReq?uc_index=%d" %
        vrf_index)
    root = xml.etree.ElementTree.fromstringlist(stdout)
    for route in root.findall('.//RouteUcSandeshData'):
        ip = route.find('src_ip')
        prefixlen = route.find('src_plen')
        if prefixlen.text == "32" and ip.text in svc:
            absent.remove(ip.text)

    if len(absent) > 1:
        print 'services not in gateway VRF'
        print absent
        return False
    return True


def contrail_svc_address_ping(prober, master):
    """
    Ensure that the specified system can reach the service IP addresses.
    """

    stdout, stderr = master.run(
        "oc get svc -o jsonpath='{.items[*].spec.clusterIP}'")
    if len(stdout) == 0:
        print 'No service IPs'
        print '\n'.join(stderr)
        return False
    serviceIPs = stdout[0].split()
    for svc in serviceIPs:
        if svc.endswith('.0.1'):
            serviceIPs.remove(svc)
            break

    success = True
    regexp = re.compile(r'(\d+) packets transmitted, (\d+) received')
    for svc in serviceIPs:
        result = 0
        stdout, stderr = prober.run("ping -c 5 %s" % svc)
        for line in stdout:
            m = regexp.match(line)
            if m:
                result = int(m.group(2))
                break
        if result != 5:
            print "ping %s" % svc
            print line
            success = False
    return success


def test_application_status(master, gateway):
    """ Returns True if the application is running

    Deployment fails is any of the pods is in Error state.

    The test succeeds if the web-front end is reachable.
    Deployment takes 5/10 mins to complete.
    """
    start = datetime.now()
    while (datetime.now() - start).seconds < (60 * 60):
        stdout, stderr = master.run(
            "oc --namespace=test get pods -o json")
        try:
            podInfo = json.loads('\n'.join(stdout))
        except Exception as ex:
            print 'Unable to decode pod information %s' % ex
            print stderr
            return False

        run_count = 0
        pending = 0
        builder = 0
        for item in podInfo['items']:
            if item['status']['phase'] == 'Failed':
                print 'pod %s Failed' % item['metadata']['name']
                return False
            elif item['status']['phase'] == 'Running':
                if (item['metadata']['name'].endswith('-build') or
                   item['metadata']['name'].endswith('-deploy')):
                    builder += 1
                    continue
                run_count += 1
            elif item['status']['phase'] == 'Pending':
                pending += 1

        if not pending and not builder and run_count >= 2:
            break
        time.sleep(180)

    for _ in range(6):
        stdout, stderr = gateway.run(
            "no_proxy=* curl http://%s:%d/articles" %
            ('rails-postgresql-example-test.router.default.svc.cluster.local',
             80))
        pattern = re.compile(r'Listing articles')
        for line in stdout:
            if pattern.search(line):
                print "Application OK"
                return True
        time.sleep(10)

    print 'Application stdout:'
    print '\n'.join(stdout)
    print 'Application stderr:'
    print '\n'.join(stderr)
    return False


def inventory_parse(filename):
    """ Parse the inventory file.

    Expects inventory to have the following format:
    [section]
    hostname ansible_ssh_host=<IP>
    """

    group_names = ['masters', 'gateways', 'nodes']
    groups = {}

    config = ConfigParser.ConfigParser(allow_no_value=True)
    with open(filename, 'r') as fp:
        config.readfp(fp)

    for section in group_names:
        try:
            group = []
            for item in config.items(section):
                group.append(item[1])
            groups[section] = group
        except ConfigParser.NoSectionError:
            pass

    return groups


def main():
    """
    stages:
      1. OpenContrail is installed (but not provisioned)
      2. OpenShift is installed
      3. OpenContrail is provisioned
      4. OpenShift services are started
      5. Test application is deployed

    common install problems:
      - control-node rejecting XMPP connections
      - service-default network marked as deleted on control-node
    """

    parser = argparse.ArgumentParser()

    parser.add_argument('--stage', type=int, help='Install stage')
    parser.add_argument('inventory')

    args = parser.parse_args()
    groups = inventory_parse(args.inventory)

    if 'masters' not in groups:
        print '%s does not define a master' % args.inventory
        sys.exit(1)

    master = Executor(groups['masters'][0])

    success = (
        contrail_api_status(master) and
        contrail_docker_status(master, netManager=args.stage >= 2) and
        contrail_services_status(master) and
        (args.stage < 3 or contrail_xmpp_sessions(master)) and
        (args.stage < 4 or openshift_system_services(master)) and
        (args.stage < 4 or contrail_control_instance_status(master))
    )

    for node in groups['nodes']:
        with Executor(node) as channel:
            ok = contrail_docker_agent(channel, node)
            if not ok:
                success = False

    for gateway in groups['gateways']:
        with Executor(gateway) as channel:
            ok = contrail_docker_agent(channel, gateway)
            if (args.stage >= 4):
                if not contrail_gateway_expect_svc_routes(channel, master,
                                                          gateway):
                    ok = False
            if not ok:
                success = False

    if args.stage >= 4 and not contrail_svc_address_ping(master, master):
        success = False

    if args.stage >= 5:
        with Executor(groups['gateways'][0]) as channel:
            if not test_application_status(master, channel):
                success = False

    del master

    if not success:
        print 'FAIL'
        sys.exit(1)

if __name__ == '__main__':
    main()
