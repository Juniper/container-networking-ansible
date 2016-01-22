# container-networking-ansible
Ansible provisioning for container networking solutions using OpenContrail

This repository contains provisioning instructions to install OpenContrail
as a network overlay for container based cluster management solutions.

The test directory defines a jenkins workflow that creates and
installs a test cluster and executes an application within the
cluster.

For support/questions:
 - Join the slack team at `slack.opencontrail.org`
 - Developers mailing list: dev@lists.opencontrail.org

The opencontrail playbook consists of the following:
  - filter_plugins/ip_filters.py
  - roles/opencontrail{,_facts,_provision}

The playbooks are designed to be addons to the existing ansible provisioning for kubernetes and openshift.

### Kubernetes

#### Network segmentation and access control
When opencontrail is used as the kubernetes network plugin, it defaults to isolate all pods according to `namespace` and a user defined tag. External traffic is restricted to services that are annotated with a ExternalIP address or have "type" set to "LoadBalancer". This causes the opencontrail public to allocate an address on the public network and assign it to all the pods in this service.

Services in the `kube-system` namespace are also available to all Pods, irrespective of the namespace of the pod. This is configured via the `cluster-service` option in /etc/kubernetes/network.conf. The cluster-service network is also connected to the underlay network where masters and nodes are present.

Pods are expected to communicate with the master via its ClusterIP address.

#### Deployment
The kubernetes ansible playbook at https://github.com/kubernetes/contrib.

- edit ansible/group_vars/all.yml
```
networking: opencontrail
```

- inventory file:
```
[opencontrail:children]
masters
nodes
gateways

[opencontrail:vars]
opencontrail_public_subnet=192.0.2.0/24
opencontrail_kube_release=1.1

```

- patch ansible/cluster.yml according to:
https://github.com/kubernetes/contrib/pull/261

- run the ansible/cluster.yml playbook (e.g. via ansible/setup.sh)

### OpenShift

#### Network segmentation and access control

There are several differences in design from a plain-vanilla kubernetes cluster deployment and an openshift deployment:
- OpenShift expects all external traffic to be delivered through the router service. The openshift router pod is a TCP load-balancer (ha-proxy by default) that performs SSL termination and delivers traffic to the pods that implement the service.
- OpenShift pods (builder/deployer) have the nasty habbit of trying to reach the master through its infrastructure IP address (rather than using the ClusterIP).
- OpenShift STI builder pods expect to be able to access external git repositories as well as package repositories for popular languages (python, ruby, etc...).
- OpenShift builder pods use the docker daemon in the node and expect it to be able to talk to the docker-repository service running as a pod (in the overlay).
- Deployer pods expect to be able to pull images from the docker-repository into the node docker daemon.

* In current test scripts, we expect the builder pods to use an http proxy in order to fetch software packages. The builder pods are spawned in the namespace of the user `project`. To provide direct external access, one would need to do so for all pods currently. Future versions of the contrail-kubernetes plugin should support source-nat for outbound access to the public network. It is also possible to add a set of prefixes that contain the software and artifact repositories used by the builder to the global `cluster-service` network.
* All the traffic between underlay and overlay is expected to occur based on the `cluster-service` gateway configured for ```default:default```

#### Deployment
- inventory file:
```
[OSEv3:children]
masters
nodes
etcd

# Set variables common for all OSEv3 hosts
[OSEv3:vars]

use_openshift_sdn = false
sdn_network_plugin_name = opencontrail

[opencontrail:children]
masters
nodes
gateways

[opencontrail:vars]
opencontrail_public_subnet=192.0.2.0/24
opencontrail_kube_release=origin-1.1
```

- provision opencontrail with the following playbook:
```
- hosts:
    - masters
    - nodes
    - gateways
  sudo: yes
  roles:
    - openshift_facts
    - opencontrail_facts
    - opencontrail
  vars:
    opencontrail_cluster_type: openshift
  tags:
    - opencontrail
```

- patch openshift-ansible with the following delta:
https://github.com/openshift/openshift-ansible/compare/master...pedro-r-marques:opencontrail

- install openshift via the ansible playbook
- run the opencontrail_provision role
