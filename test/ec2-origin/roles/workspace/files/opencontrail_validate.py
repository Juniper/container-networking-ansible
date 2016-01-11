import argparse
import json
import paramiko
import re

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

    def __del__(self):
        """ Destructor """
        self._ssh_client.close()


def expect_listen_ports(channel, expected):
    stdout, stderr = channel.run('netstat -ntl')

    absent = expected

    regexp = re.compile(r'(tcp\s+[0-9]+\s+[0-9]+\s+([0-9\.]+):([0-9]+)|tcp6\s+[0-9]+\s+[0-9]+\s+:::([0-9]+))\s+(.*)\s+LISTEN')
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
    stdout, stderr = channel.run("docker ps --format='{{.ID}} {{.Names}}'", sudo=True)
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
    containerNames = ['contrail-control', 'contrail-api', 'contrail-schema', 'ifmap-server']
    if netManager:
        containerNames.append('kube-network-manager')
    absent = expect_docker_running(channel, containerNames)
    if len(absent) > 0:
        print "Containers not running: ", absent
        return False
    return True

def contrail_api_status(channel):
    stdout, stderr = channel.run('curl http://localhost:8082')
    try:
        desc = json.loads('\n'.join(stdout))
    except Exception as ex:
        print 'Unable to connect to the contrail-api server'
        print '\n'.join(stderr)
        return False
    return True

def contrail_xmpp_sessions(channel):
    stdout, stderr = channel.run("netstat -nt | grep -E ':5269\s+.*ESTABLISHED'")
    success = len(stdout) == 3
    if not success:
        print 'XMPP sessions:'
        print '\n'.join(stdout)
    return success

"""
stages:
  1. OpenContrail is installed (but not provisioned)
  2. OpenShift is installed
  3. OpenContrail is provisioned
  4. OpenShift services are started
"""
def main():
    parser = argparse.ArgumentParser()

    parser.add_argument('--stage', type=int, help='Install stage')
    parser.add_argument('master')

    args = parser.parse_args()
    channel = Executor(args.master)

    contrail_api_status(channel)
    contrail_services_status(channel)
    contrail_docker_status(channel, netManager=args.stage >= 2)
    if args.stage >= 3:
        contrail_xmpp_sessions(channel)


if __name__ == '__main__':
    main()