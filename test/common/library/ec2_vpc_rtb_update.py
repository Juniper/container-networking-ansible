#!/usr/bin/python
#
# Module that updates the routing table of a VPC.
#
# It updates (create or replace routes pointing to instance).
# It ignores gateway routes and doesn't flush instance routes that are no
# longer specified.
#

# import module snippets
from ansible.module_utils.basic import *
from ansible.module_utils.ec2 import *

import boto.vpc


def rtb_update(connection, rtb, routes):
    """ Update the table in order to ensure that the route is present """
    changed = False
    for route in routes:
        if route.get('gw') == 'igw':
            continue

        existing_rt = filter(
            lambda x: x.destination_cidr_block == route['dest'], rtb.routes)

        if len(existing_rt) > 0:
            if existing_rt[0].instance_id == route.get('gw'):
                continue
            success = connection.replace_route(
                rtb.id, route['dest'], instance_id=route.get('gw'))
        else:
            success = connection.create_route(
                rtb.id, route['dest'], instance_id=route.get('gw'))
        if success:
            changed = True
    return changed


def rtb_delete(connection, rtb, routes):
    """ Delete a set of routes from the table """
    changed = False
    for route in routes:
        if route.get('gw') == 'igw':
            continue

        existing_rt = filter(
            lambda x: x.destination_cidr_block == route['dest'], rtb.routes)
        if len(existing_rt) == 0:
            continue

        connection.delete_route(rtb.id, route['dest'])
        changed = True

    return changed


def main():
    argument_spec = ec2_argument_spec()
    argument_spec.update(dict(
        vpc_id=dict(required=True),
        subnets=dict(type='list', required=True),
        routes=dict(type='list'),
        state=dict(choices=['present', 'absent'], default='present')
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

    tables = connection.get_all_route_tables(
        filters={'vpc_id': module.params.get('vpc_id')}
    )

    def match_by_subnets(t):
        subnet_ids = map(lambda x: x.subnet_id, t.associations)
        return set(subnet_ids) == set(module.params.get('subnets'))

    selected_tables = filter(match_by_subnets, tables)

    if len(selected_tables) != 1:
        if len(selected_tables) > 1:
            module.fail_json(msg="Multiple route tables selected")

        rtb = connection.create_route_table(module.params.get('vpc_id'))
        for subnet_id in module.params.get('subnets'):
            connection.associate_route_table(rtb.id, subnet_id)
    else:
        rtb = selected_tables[0]

    changed = False
    if module.params.get('state') == 'present':
        changed = rtb_update(connection, rtb, module.params.get('routes'))
    elif module.params.get('state') == 'absent':
        changed = rtb_delete(connection, rtb, module.params.get('routes'))

    module.exit_json(changed=changed, rtb_id=rtb.id)

main()
