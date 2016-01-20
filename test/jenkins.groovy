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

@NonCPS
def inventory_match_item(text) {
    def matcher = (text =~ /^[\w-_\.]+/)
    matcher ? matcher[0] : null
}

def k8s_deploy(deployer) {
    def playbooks = [
        'resolution.yml',
        'cluster.yml'
    ]

    echo "Start k8s deploy stage on ${deployer}"

    // Use an integer as iterator so that it is serializable.
    // The "sh" step requires local variables to serialize.
    for (int i = 0; i < playbooks.size(); i++) {
        def playbook = playbooks[i]
    	echo "playbook ${playbook}"
        sh "ssh ${ssh_options} ubuntu@${deployer} ansible-playbook -i src/contrib/ansible/inventory src/contrib/ansible/${playbook}"
    }
}

def origin_deploy(deployer) {
    def playbooks = [
        'system-install.yml',
        'opencontrail.yml',
        'config.yml',
        'opencontrail_provision.yml',
        'openshift_provision.yml',
        'applications.xml'
    ]

    echo "Start openshift deploy stage on ${deployer}"

    // Use an integer as iterator so that it is serializable.
    // The "sh" step requires local variables to serialize.
    for (int i = 0; i < playbooks.size(); i++) {
        def playbook = playbooks[i]
    	echo "playbook ${playbook}"
        sh "ssh ${ssh_options} centos@${deployer} '(cd src/openshift-ansible; ansible-playbook -i inventory/byo/hosts playbooks/byo/${playbook})'"
        // version 1.15 of the script-security plugin allows less-than but not greater-than comparissons
        if (0 < i) {
            try {
                sh "ssh ${ssh_options} centos@${deployer} '(cd src/openshift-ansible; python playbooks/byo/opencontrail_validate.py --stage ${i} inventory/byo/hosts)'"
            } catch (AbortException ex) {
                // openshift config playbook restarts docker and systemd will fail to restart some of the dependent
                // opencontrail services.
                if (i == 2) {
                    sh "ssh ${ssh_options} centos@${deployer} '(cd src/openshift-ansible; ansible-playbook -i inventory/byo/hosts playbooks/byo/systemd_workaround.yml)'"
                    sleep 60
                    sh "ssh ${ssh_options} centos@${deployer} '(cd src/openshift-ansible; python playbooks/byo/opencontrail_validate.py --stage ${i} inventory/byo/hosts)'"   
                } else {
                    throw ex
                }
            }
        }
    }
}

def k8s_validate(deployer) {
    retry(15) {
        try {
            sh "ssh ${ssh_options} ubuntu@${deployer} ansible-playbook -i src/contrib/ansible/inventory src/contrib/ansible/validate.yml"
        } catch (ex) {
            msg "k8s_validate: ${ex}"
            sleep 180
            throw ex
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
        } catch (AbortException ex) {
            echo "${ex}"
            sleep 60
            throw ex
        }
        def slaves = match_connected_slaves(status)
        if (slaves != '2') {
            sleep 60
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
                echo "${ex}"
                input 'Debug k8s'
                throw ex
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
                echo "${ex}"
                input 'Debug openshift'
                throw ex
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
