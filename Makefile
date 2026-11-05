.PHONY: test test-fast test-python test-java help

help:
	@echo "make test-fast   - run all fast unit tests (default)"
	@echo "make test        - alias for test-fast"
	@echo "make test-python - pytest in main/xiaozhi-server"
	@echo "make test-java   - mvn test in main/manager-api"

test: test-fast

test-fast: test-python test-java

test-python:
	cd main/xiaozhi-server && python -m pytest -x -q

test-java:
	cd main/manager-api && mvn -B -q test -DfailIfNoTests=false
