# Public entry point. HTTP only: with no domain there is no ACM certificate (ADR 0006, README).
#   /api/*                   -> order-service
#   /realms/*, /resources/*  -> Keycloak (the browser logs in through the same hostname)
#   everything else          -> frontend (nginx)

resource "aws_lb" "main" {
  name               = var.project
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id
}

resource "aws_lb_target_group" "order" {
  name                 = "${var.project}-order"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30 # the default of 300 s makes every deploy slow
  health_check {
    path    = "/actuator/health/readiness"
    matcher = "200"
  }
}

resource "aws_lb_target_group" "frontend" {
  name                 = "${var.project}-frontend"
  port                 = 80
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    path    = "/"
    matcher = "200"
  }
}

resource "aws_lb_target_group" "keycloak" {
  name                 = "${var.project}-keycloak"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    path    = "/realms/orderflow"
    matcher = "200"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.order.arn
  }
  condition {
    path_pattern { values = ["/api/*"] }
  }
}

resource "aws_lb_listener_rule" "keycloak" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 20
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.keycloak.arn
  }
  condition {
    path_pattern { values = ["/realms/*", "/resources/*"] }
  }
}
