output "vpc_id" {
  description = "REACT VPC ID"
  value       = aws_vpc.main.id
}

output "subnet_id" {
  description = "Public subnet ID"
  value       = aws_subnet.public.id
}

output "security_group_id" {
  description = "Security group ID"
  value       = aws_security_group.app.id
}

output "instance_id" {
  description = "EC2 instance ID"
  value       = aws_instance.app.id
}

output "public_ip" {
  description = "EC2 Elastic IP"
  value       = aws_eip.app.public_ip
}

output "instance_profile_name" {
  description = "EC2 IAM instance profile"
  value       = aws_iam_instance_profile.ec2.name
}
