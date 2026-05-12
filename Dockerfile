FROM java:8
LABEL "author"="tl"
RUN mkdir /GeoBI
COPY ./bin/ /GeoBI/bin/
COPY ./config/ /GeoBI/config/
COPY ./lib/ /GeoBI/lib/
COPY static /GeoBI/static
ENV TZ=Asia/Shanghai
EXPOSE 8080
WORKDIR /GeoBI
ENTRYPOINT java -server -Xms2G -Xmx2G -Dspring.profiles.active=config -Dfile.encoding=UTF-8 -cp "lib/*" datart.DatartServerApplication