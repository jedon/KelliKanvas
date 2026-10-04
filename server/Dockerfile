FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY KelliKanvas.Server/KelliKanvas.Server.csproj KelliKanvas.Server/
RUN dotnet restore KelliKanvas.Server/KelliKanvas.Server.csproj
COPY KelliKanvas.Server/ KelliKanvas.Server/
RUN dotnet publish KelliKanvas.Server/KelliKanvas.Server.csproj -c Release --no-restore -o /out /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app
ENV ASPNETCORE_URLS=http://+:8080
EXPOSE 8080
COPY --from=build /out .
USER $APP_UID
ENTRYPOINT ["dotnet", "KelliKanvas.Server.dll"]
