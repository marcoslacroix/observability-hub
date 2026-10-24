Spring Boot + Pyroscope Memory Allocation Profiler

Este projeto é uma aplicação de demonstração em Spring Boot criada para testar e visualizar o profiling de alocação de memória utilizando o Grafana Pyroscope. O projeto inclui um controlador com endpoints propositadamente projetados para gerar pressão de heap e alocações de objetos, permitindo a análise de flamegraphs sem exigir nenhuma alteração invasiva no código da aplicação (utilizando o agente Java via variável de ambiente).

🚀 O que este projeto faz?

A classe AllocationController expõe dois endpoints para simular diferentes cenários de uso de memória:

/alloc/bytes: Aloca blocos grandes de bytes (byte[] de 1 MB cada) até atingir o limite solicitado, simulando o consumo massivo de memória em arrays.

/alloc/objects: Cria um grande volume de pequenos objetos (String e instâncias de HashMap), simulando alta pressão sobre o Garbage Collector (GC).

🛠️ Requisitos

Docker e Docker Compose instalados na máquina.

Java 17+ e Maven (caso queira compilar o projeto localmente).

📂 Estrutura do Projeto

Certifique-se de que a estrutura do seu projeto na raiz contenha os seguintes arquivos para rodar com o Pyroscope:

meu-projeto-spring/
├── Dockerfile
├── docker-compose.yml
├── pyroscope.jar          # Agente Java do Pyroscope baixado da release oficial
├── pom.xml
└── src/
    └── main/
        └── java/
            └── com/
                └── example/
                    └── pyroscope/
                        └── pyroscope/
                            └── AllocationController.java


⚙️ Configuração e Execução

Passo 1: Baixar o Agente do Pyroscope

Na raiz do seu projeto (onde está o docker-compose.yml), baixe o agente Java oficial:

wget https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar


Passo 2: Compilar a Aplicação Spring Boot

Gere o pacote executável .jar do projeto:

mvn clean package -DskipTests


(Confirme se o nome gerado na pasta target/ confere com o configurado no seu Dockerfile, por exemplo: pyroscope-0.0.1-SNAPSHOT.jar)

Passo 3: Subir o ambiente com Docker Compose

Execute o comando para compilar a imagem da aplicação e subir tanto o Spring Boot quanto o servidor Pyroscope:

docker-compose up --build


🧪 Testando os Endpoints de Alocação

Com o ambiente rodando, você pode disparar requisições para forçar o consumo de memória e gerar dados para o perfilador:

Gerar alocação de blocos de bytes (ex: 50 MB):

curl "http://localhost:8080/alloc/bytes?mb=50"


Gerar alocação de muitos objetos pequenos (ex: 500.000 itens):

curl "http://localhost:8080/alloc/objects?items=500000"


📈 Visualizando os Dados no Pyroscope

Abra o navegador e acesse o painel do Pyroscope: http://localhost:4040

No menu superior esquerdo, selecione a aplicação correspondente configurada (minha-api-spring ou equivalente).

Selecione o tipo de métrica voltado a alocação (alloc).

Navegue pelo Flamegraph para inspecionar exatamente quais métodos (allocateBytes ou allocateObjects) e classes consumiram mais memória na pilha de execução da JVM.
