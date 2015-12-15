#!/usr/bin/python
#
# Retrieve information on an existing VPC.
#

# import module snippets
from ansible.module_utils.basic import *
from ansible.module_utils.ec2 import *

import boto.vpc


def main():
    argument_spec = ec2_argument_spec()
    argument_spec.update(dict(
        resource_tags=dict(type='dict', required=True)
    ))
    module = AnsibleModule(argument_spec=argument_spec)

    ec2_url, aws_access_key, aws_secret_key, region = get_ec2_creds(module)

    if not region:
        module.fail_json(msg="region must be specified")

    try:
        connection = boto.vpc.connect_to_region(
            region,
            aws_access_key_id=aws_access_key,
            aws_secret_access_key=aws_secret_key)
    except boto.exception.NoAuthHandlerFound, e:
        module.fail_json(msg=str(e))

    vpcs = connection.get_all_vpcs()
    vpcs_w_resources = filter(
        lambda x: x.tags == module.params.get('resource_tags'), vpcs)
    if len(vpcs_w_resources) != 1:
        if len(vpcs_w_resources) == 0:
            module.fail_json(msg="No vpc found")
        else:
            module.fail_json(msg="Multiple VPCs with specified resource_tags")

    vpc = vpcs_w_resources[0]

    subnets = connection.get_all_subnets(filters={'vpc_id': vpc.id})

    def subnet_data(s):
        d = s.__dict__
        del d["connection"]
        del d["region"]
        return d

    data = map(subnet_data, subnets)
    facts = {
        'ec2_vpc': {
            'id': vpc.id,
            'subnets': data
        }
    }
    module.exit_json(changed=False, ansible_facts=facts)

main()
