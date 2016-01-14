import hudson.AbortException

ssh_options = '-o StrictHostKeyChecking=no -o ForwardAgent=yes'

def getDeployerHostname() {
    def inventory = readFile('cluster.status')

    def section = false
    def hostname

    for (line in inventory.split('\n')) {
        if (line == '[deployer]') {
           section = true
           continue
        }
        if (section) {
	       hostname = inventory_match_item(line)
           break
        }
    }
    return hostname
}

def getMasterIP() {
    def inventory = readFile('inventory.cluster')
    def section = false
    def ipAddress

    for (line in inventory.split('\n')) {
        if (line == '[masters]') {
           section = true
           continue
        }
        if (section) {
           ipAddress = inventory_match_ssh_host(line)
           break
        }
    }
    return ipAddress    
}

@NonCPS
def inventory_match_item(text) {
    def matcher = (text =~ /^[\w-_\.]+/)
    matcher ? matcher[0] : null
}

@NonCPS
def inventory_match_ssh_host(text) {
    def matcher = (text =~ /^[\w-_\.]+\s+ansible_ssh_host=([0-9\.]+)/)
    matcher ? matcher[1] : null
}

def k8s_deploy(deployer) {
    playbooks = [
        'resolution.yml',
        'cluster.yml'
    ]

    echo "Start deploy stage on ${deployer}"

    // Use an integer as iterator so that it is serializable.
    // The "sh" step requires local variables to serialize.
    for (int i = 0; i < playbooks.size(); i++) {
        def playbook = playbooks[i]
    	echo "playbook ${playbook}"
        sh "ssh ${ssh_options} ubuntu@${deployer} ansible-playbook -i src/contrib/ansible/inventory src/contrib/ansible/${playbook}"
    }
}

def origin_deploy(deployer) {
    playbooks = [
        'system-install.yml',
        'opencontrail.yml',
        'config.yml',
        'opencontrail_provison.yml',
        'openshift_provision.yml'
    ]

    echo "Start deploy stage on ${deployer}"
    masterIP = getMasterIP()
    echo "master: ${masterIP}"

    // Use an integer as iterator so that it is serializable.
    // The "sh" step requires local variables to serialize.
    for (int i = 0; i < playbooks.size(); i++) {
        def playbook = playbooks[i]
    	echo "playbook ${playbook}"
        sh "ssh ${ssh_options} centos@${deployer} ansible-playbook -i src/openshift-ansible/inventory/byo/hosts src/openshift-ansible/playbooks/byo/${playbook}"
        if (i > 0) {
            sh "ssh ${ssh_options} centos@${deployer} python src/openshift-ansible/playbooks/byo/opencontrail_validate.py --stage ${i} ${masterIP}"
        }
    }
}

def k8s_validate(deployer) {
    retry(15) {
        try {
            sh "ssh ${ssh_options} ubuntu@${deployer} ansible-playbook -i src/contrib/ansible/inventory src/contrib/ansible/validate.yml"
        } catch (AbortException e) {
            Thread.sleep(60 * 1000)
            error('Cluster not ready')
        }
    }
}

def k8s_run_examples(deployer) {
    sh "ssh ${ssh_options} ubuntu@${deployer} ansible-playbook -i src/contrib/ansible/inventory src/contrib/ansible/examples.yml"
}

def guestbook_status(deployer) {
    retry(15) {
        def status
        try {
            sh "ssh ${ssh_options} ubuntu@${deployer} curl http://172.16.0.252:3000/info > guestbook.status"
            status = readFile('guestbook.status')
        } catch (AbortException e) {
            echo e.getMessage()
            Thread.sleep(60 * 1000)
            error('Service not responding')
        }
        def slaves = match_connected_slaves(status)
        if (slaves != '2') {
            Thread.sleep(60 * 1000)
            error("redis slaves: ${slaves}")
        }
    }
}

@NonCPS
def match_connected_slaves(status) {
    def matcher = (status =~ /(?m)^connected_slaves:(\d+)/)
    matcher ? matcher[0][1] : null
}

test_ec2_k8s_basic = {
    node {
        // git url: 'https://github.com/Juniper/container-networking-ansible.git'
        checkout scm

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
                    k8s_deploy(deployer)

                    k8s_validate(deployer)

                    k8s_run_examples(deployer)

                    // verify
                    guestbook_status(deployer)
                }
            } catch(ex) {
                echo ex.getMessage()
                ex.printStackTrace()
                input 'Debug'
            } finally {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'k8s-provisioner', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    // delete cluster
                    sh 'ansible-playbook -i cluster.status clean.yml'
                }
            }
        }
    }
}

test_ec2_openshift_basic = {
    node {
        // Checkout repository in workspace.
        checkout scm

        dir('test/ec2-origin') {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'k8s-provisioner', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                // tags: cluster, key-data, deployer-install, workspace
                sh "ansible-playbook -i localhost playbook.yml --tags=cluster -e workspace_id=${env.BUILD_NUMBER}"
            }

            sh "ansible-playbook -i localhost key-data.yml"

            def deployer = getDeployerHostname()

            try {
                sshagent(credentials: ["k8s"]) {
                    sh "ansible-playbook -i cluster.status playbook.yml --tags=deployer-install,workspace -e workspace_id=${env.BUILD_NUMBER}"
                    origin_deploy(deployer)
                }
                input 'Install complete'
            } catch(ex) {
                echo ex.getMessage()
                ex.printStackTrace()
                input 'Debug'
            } finally {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'k8s-provisioner', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    // delete cluster
                    sh 'ansible-playbook -i cluster.status clean.yml'
                }
            }
        }
    }
}

def getTestMatrix() {
    tests = [
        ec2_k8s_basic: test_ec2_k8s_basic,
        ec2_openshift_basic: test_ec2_openshift_basic
    ]
    return tests
}

return this;
