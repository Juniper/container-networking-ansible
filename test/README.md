# EC2 tests

By default, EC2 limits clients to 5 VPCs. As such it is not practical to create a VPC per test job. All the jenkins jobs share one VPC which is created by the aws-ci-provisioning.

This VPC uses the CIDR block 10.0.0.0/16 and the following 2 subnets:

| IP Prefix | Name | Description |
|-----------|------|-------------|
| 10.0.0.0/20 |  public | For instances with public IP addresses |
| 10.0.32.0/20 | private | For instances without public IP addresses |

The networks used by the test clusters should exclude the VPC CIDR block.

`kube-network-manager` defaults to using the same CIDR block as its private IP block. The tests override the private IP block to 10.32.0.0/16.

## kubernetes

The tests require network access between the underlay and the public network. In addition, future tests may require connectivity between the underlay network and monitoring services.

| IP Prefix | Name | Description |
|-----------|------|-------------|
| 172.16.(8 * (job_id % 32)).0/21 | public network | |
| 172.18.0.0/20 | public network | default (manual tests) | 
| 10.(192 + job_id % 32).0.0/16 | service address range | |
| 10.64.0.0/16 | service address range | default (manual tests) |



## openshift

| IP Prefix | Name | Description |
|-----------|------|-------------|
| 172.20.( 8 * (job_id % 32)).0/21 | public network | |
| 172.18.64.0/20 | public network | default (manual tests) |
| 10.(160 + job_id % 32).0.0/16 | service address range | |
| 10.65.0.0/16 | service address range | default (manual tests) |

