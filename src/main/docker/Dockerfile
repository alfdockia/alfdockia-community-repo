FROM ${docker.acs.image}:${alfresco.platform.version}

ARG TOMCAT_DIR=/usr/local/tomcat
ARG USERNAME=${alfresco.platform.docker.user}

USER root

# Copy Dockerfile to avoid an error if no JARs exist
COPY Dockerfile extensions/*.jar $TOMCAT_DIR/webapps/alfresco/WEB-INF/lib/

# Copy Dockerfile to avoid an error if no AMPs exist
COPY Dockerfile extensions/*.amp $TOMCAT_DIR/amps/
RUN java -jar $TOMCAT_DIR/alfresco-mmt/alfresco-mmt*.jar install \
              $TOMCAT_DIR/amps $TOMCAT_DIR/webapps/alfresco -directory -nobackup -force

COPY alfresco-global.properties $TOMCAT_DIR/shared/classes/alfresco-global.properties
COPY alfresco $TOMCAT_DIR/shared/classes/alfresco
COPY dev-log4j2.properties $TOMCAT_DIR/shared/classes/alfresco/extension
COPY disable-webscript-caching-context.xml $TOMCAT_DIR/shared/classes/alfresco/extension

# 1. Instalar dependencias y configurar el repositorio de Docker
RUN dnf install -y dnf-plugins-core && \
    dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo && \
    dnf install -y docker-ce-cli containerd.io && \
    dnf clean all

# 2. Crear grupo "docker" con el mismo GID que en el host (ej: 999)
# NOTA: Reemplaza "999" con el GID de tu grupo "docker" en el host (ver: `ls -n /var/run/docker.sock`)
#RUN groupadd -g 999 docker

# --- Permisos docker.sock: el GID del host es 999 ---
# Crear grupo con GID=999 si no existe y añadir alfresco
RUN set -eux; \
    if ! getent group 999 >/dev/null 2>&1; then \
      groupadd -g 999 dockerhost; \
    fi; \
    usermod -aG 999 ${USERNAME}

RUN set -eux; \
    if ! getent group 101 >/dev/null 2>&1; then \
      groupadd -g 101 dockerhost; \
    fi; \
    usermod -aG 101 ${USERNAME}

# 3. Añadir usuario "alfresco" al grupo "docker"
RUN usermod -aG docker alfresco

USER ${USERNAME}
