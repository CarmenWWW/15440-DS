all: Proxy.class Server.class RMIInterface.class RemoteFile.class Cache.class CacheFile.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
