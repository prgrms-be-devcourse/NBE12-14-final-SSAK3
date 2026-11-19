provider "aws" {
  region              = "ap-northeast-2"
  allowed_account_ids = ["533267244952"]

  default_tags {
    tags = {
      Project   = "react"
      ManagedBy = "terraform"
    }
  }
}