def ssh_options = '-o StrictHostKeyChecking=no -o ForwardAgent=yes'

def deploy() {
    playbooks = [
        'resolution.yml',
        'cluster.yml',
        'validate.yml',
        'examples.yml'
    ]
    echo 'Start deploy stage'
    for (String playbook : playbooks) {
    	echo "playbook ${playbook}"
        sh "ssh ${ssh_options} ansible-playbook -i src/contrib/ansible/inventory ${playbook}"
    }
}

def guestbook_status() {
    sh "ssh %{ssh_options} curl http://172.16.0.252:3000/info > guestbook.status"
}

test_ec2_k8s_basic = {
    node {
        // git url: 'https://github.com/Juniper/container-networking-ansible.git'
        dir('test/ec2-k8s') {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'k8s-provisioner', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                // create cluster
                sh "ansible-playbook -i localhost playbook.yml --tags=create -e job_id=${env.BUILD_NUMBER}"
            }

            try {
                sshagent(credentials: ["k8s"]) {
                    sh 'ansible-playbook -i cluster.status playbook.yml --tags=deployer-install'
                    sh 'ansible-playbook -i cluster.status playbook.yml --tags=workspace'
                    // ssh client steps
                    deploy()

                    // verify
                    guestbook_status()
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
