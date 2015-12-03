ssh_options = '-o StrictHostKeyChecking=no -o ForwardAgent=yes'

def getDeployerHostname() {
    def inventory = readFile('cluster.status')

    def section = false
    def hostname

    for (line in inventory.split('\n')) {
        if (line == '[management]') {
           section = true
           continue
        }
        if (section) {
           matcher = (line =~ /^\w+/)
           hostname = matcher[0]
           break
        }
    }
    return hostname
}

def deploy(deployer) {
    playbooks = [
        'resolution.yml',
        'cluster.yml',
        'validate.yml',
        'examples.yml'
    ]

    echo "Start deploy stage on ${deployer}"

    for (playbook in playbooks) {
    	echo "playbook ${playbook}"
        sh "ssh ${ssh_options} ${deployer} ansible-playbook -i src/contrib/ansible/inventory ${playbook}"
    }
}

def guestbook_status(deployer) {
    sh "ssh ${ssh_options} ${deployer} curl http://172.16.0.252:3000/info > guestbook.status"
}

test_ec2_k8s_basic = {
    node {
        // git url: 'https://github.com/Juniper/container-networking-ansible.git'
        dir('test/ec2-k8s') {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'k8s-provisioner', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                // create cluster
                sh "ansible-playbook -i localhost playbook.yml --tags=create -e job_id=${env.BUILD_NUMBER}"
            }

	    def deployer = getDeployerHostname()

            try {
                sshagent(credentials: ["k8s"]) {
                    sh 'ansible-playbook -i cluster.status playbook.yml --tags=deployer-install'
                    sh 'ansible-playbook -i cluster.status playbook.yml --tags=workspace'
                    // ssh client steps
                    deploy(deployer)

                    // verify
                    guestbook_status(deployer)
                }
            } finally {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'k8s-provisioner', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    // delete cluster
                    sh 'ansible-playbook -i cluster.status clean.yml'
                }
            }
        }
    }
}

test_noop = {
    node {
        // jenkins-<JOB_NAME>-<BUILD_NUMBER>
        echo env.BUILD_TAG
    }
}

def getTestMatrix() {
    tests = [
        ec2_k8s_basic: test_ec2_k8s_basic,
        noop: test_noop,
    ]
    return tests
}

return this;
