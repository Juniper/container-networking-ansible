import netaddr

class FilterModule(object):
	''' Custom ansible filter '''

	@staticmethod
	def netmask2prefixlen(data):
		net = netaddr.IPNetwork('0.0.0.0/%s' % data)
		return net.prefixlen

	def filters(self):
		return {
			"netmask2prefixlen": self.netmask2prefixlen
		}