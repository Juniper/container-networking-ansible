#!/usr/bin/python

"""
deployment_config_set [-i] filename

Edit an openshift deployment config template.
"""

import argparse
import json
import sys

class DeploymentConfig(object):
	def __init__(self, fp):
		self._template = json.load(fp)

	def _get_item(self, kind):
		for item in self._template['items']:
			if item['kind'] == kind:
				return item
		return None

	def set_service(self, clusterIP):
		svc = self._get_item("Service")
		svc['spec']['clusterIP'] = clusterIP

	def store(self, fp):
		return json.dump(self._template, fp, indent=4)

def main():
	parser = argparse.ArgumentParser()
	parser.add_argument('-i', help='Modify input file')
	parser.add_argument('--output', type=str, help='Output filename')
	parser.add_argument('--service', type=str, help='Set service cluster IP address')
	parser.add_argument('filename', help='deployment config JSON file')

	args = parser.parse_args()

	if args.i and args.output:
		print 'Invalid arguments: -i and --output are mutually exclusive'
		sys.exit(1)

	with open(args.filename, 'r') as fp:
		deployment = DeploymentConfig(fp)

	if args.service:
		deployment.set_service(args.service)

	if args.i or args.output:
		outputFile = args.output
		if not outputFile:
			outputFile = args.filename
		with open(outputFile, 'w') as fp:
			deployment.store(fp)
	else:
		deployment.store(sys.stdout)

if __name__ == '__main__':
	main()